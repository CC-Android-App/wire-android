/**
  * Wire
  * Copyright (C) 2016 Wire Swiss GmbH
  *
  * This program is free software: you can redistribute it and/or modify
  * it under the terms of the GNU General Public License as published by
  * the Free Software Foundation, either version 3 of the License, or
  * (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU General Public License for more details.
  *
  * You should have received a copy of the GNU General Public License
  * along with this program.  If not, see <http://www.gnu.org/licenses/>.
  */
package com.waz.zclient.camera


import android.graphics.{Rect, SurfaceTexture}
import android.hardware.Camera
import android.hardware.Camera.{AutoFocusCallback, PictureCallback, ShutterCallback}
import android.os.{Build, Handler, HandlerThread}
import com.waz.ZLog
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.events.{EventContext, Signal}
import com.waz.zclient.utils.SquareOrientation
import timber.log.Timber

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}

class CameraPreviewController(implicit eventContext: EventContext) {

  import CameraPreviewController._

  implicit val logTag = ZLog.logTagFor[CameraPreviewController]

  implicit val cameraExecutionContext = new ExecutionContext {
    private val cameraHandler = {
      val cameraThread = new HandlerThread(CameraPreviewController.CAMERA_THREAD_ID)
      cameraThread.start
      new Handler(cameraThread.getLooper)
    }
    override def reportFailure(cause: Throwable): Unit = Timber.e(cause, "Problem executing on Camera Thread.")
    override def execute(runnable: Runnable): Unit = cameraHandler.post(runnable)
  }

  protected[camera] val camInfos = try {
    val info = new Camera.CameraInfo
    Seq.tabulate(Camera.getNumberOfCameras) { i =>
      Camera.getCameraInfo(i, info)
      CameraInfo(i, CameraFacing.getFacing(info.facing), info.orientation)
    }
  } catch {
    case e: Throwable =>
      Timber.w(e, "Failed to retrieve camera info - camera is likely unavailable")
      Seq.empty
  }

  //TODO tidy this up
  @volatile private var currentCamera = Option.empty[Camera]

  private var loadFuture = CancellableFuture.cancelled[PreviewSize]()

  private var currentCamInfo = camInfos.headOption //save this in global controller for consistency during the life of the app

  val currentFlashMode = Signal(FlashMode.OFF)

  val deviceOrientation = Signal(SquareOrientation.PORTRAIT_STRAIGHT)

  def getCurrentCameraFacing = currentCamInfo.map(_.cameraFacing)

  /**
    * Cycles the currentCameraInfo to point to the next camera in the list of camera devices. This does NOT, however,
    * start the camera. The previos camera should be released and then openCamera() should be called again
    */
  def setNextCamera() = {
    currentCamInfo.foreach(c => currentCamInfo = camInfos.lift((c.id + 1) % camInfos.size))
  }

  /**
    * Returns a Future of a PreviewSize object representing the preview size that the camera preview will draw to.
    * This should be used to calculate the aspect ratio for re-sizing the texture
    */
  def openCamera(texture: SurfaceTexture, w: Int, h: Int) = {
    loadFuture.cancel()
    loadFuture = currentCamInfo.fold(CancellableFuture.cancelled[PreviewSize]()) { info =>
      try {
        CancellableFuture {
          val c = Camera.open(info.id)
          currentCamera = Some(c)
          c.setPreviewTexture(texture)

          var previewSize: PreviewSize = PreviewSize(0, 0)
          setParams(c) { pms =>
            previewSize = getPreviewSize(pms, w, h)
            pms.setPreviewSize(previewSize.w.toInt, previewSize.h.toInt)
            setPictureSize(pms, info.cameraFacing)
            deviceOrientation.currentValue.foreach { o =>
              pms.setRotation(getCameraRotation(o.orientation, info))
              c.setDisplayOrientation(getPreviewOrientation(0, info))
            }
            currentFlashMode.currentValue.foreach { fm =>
              if (getSupportedFlashModes.contains(fm)) pms.setFlashMode(fm.mode) else pms.setFlashMode(FlashMode.OFF.mode)
            }
            if (clickToFocusSupported) setFocusMode(pms, FOCUS_MODE_AUTO) else setFocusMode(pms, FOCUS_MODE_CONTINUOUS_PICTURE)
          }
          c.startPreview
          previewSize
        }
      } catch {
        case e: Throwable =>
          Timber.w(e, "Failed to open camera - camera is likely unavailable")
          CancellableFuture.cancelled[PreviewSize]()
      }
    }
    loadFuture
  }

  def takePicture(onShutter: => Unit) = {
    val promise = Promise[Array[Byte]]()
    Future {
      currentCamera match {
        case Some(c) =>
          c.takePicture(new ShutterCallback {
            override def onShutter(): Unit = Future(onShutter())(Threading.Ui)
          }, null, new PictureCallback {
            override def onPictureTaken(data: Array[Byte], camera: Camera): Unit = {
              camera.startPreview() //restarts the preview as it gets stopped by camera.takePicture()
              promise.success(data)
            }
          })
        case _ => promise.failure(new RuntimeException("Take picture cannot be called while the camera is closed"))
      }
    }
    promise.future
  }

  def releaseCamera() = {
    loadFuture.cancel()
    Future {
      currentCamera.foreach { c =>
        c.stopPreview
        c.release
        currentCamera = None
      }
    }
  }

  def setFocusArea(touchRect: Rect, w: Int, h: Int) = {
    val promise = Promise[Unit]()
    Future {
      if (touchRect.width == 0 || touchRect.height == 0) promise.success(())
      else {
        currentCamera match {
          case Some(c) if clickToFocusSupported =>

            val focusArea = new Camera.Area(new Rect(
              touchRect.left * camCoordsRange / w - camCoordsOffset,
              touchRect.top * camCoordsRange / h - camCoordsOffset,
              touchRect.right * camCoordsRange / w - camCoordsOffset,
              touchRect.bottom * camCoordsRange / h - camCoordsOffset
            ), focusWeight)

            setParams(c)(_.setFocusAreas(List(focusArea).asJava))
            c.autoFocus(new AutoFocusCallback {
              override def onAutoFocus(s: Boolean, cam: Camera) = {
                if (!s) Timber.w("Focus was unsuccessful - ignoring")
                promise.success(())
              }
            })
          case _ => promise.success(())
        }
      }
    }
    promise.future
  }

  def getSupportedFlashModes = currentCamera.fold(Set.empty[String]) { c =>
    Option(c.getParameters.getSupportedFlashModes).fold(Set.empty[String])(_.asScala.toSet)
  }.map(FlashMode.get(_))

  currentFlashMode.on(cameraExecutionContext) { fm =>
    currentCamera.foreach(setParams(_)(_.setFlashMode(fm.mode)))
  }

  deviceOrientation.on(cameraExecutionContext) { o =>
    currentCamInfo.foreach { info =>
      currentCamera.foreach { c =>
        setParams(c)(_.setRotation(getCameraRotation(o.orientation, info)))
      }
    }
  }

  private def clickToFocusSupported = currentCamera.fold(false) { c =>
    c.getParameters.getMaxNumFocusAreas > 0 && supportsFocusMode(c.getParameters, FOCUS_MODE_AUTO)
  }

  private def supportsFocusMode(pms: Camera#Parameters, mode: String) =
    Option(pms.getSupportedFlashModes).fold(false)(_.contains(mode))

  private def setFocusMode(pms: Camera#Parameters, mode: String) = if (supportsFocusMode(pms, mode)) pms.setFocusMode(mode)

  private def getPreviewSize(pms: Camera#Parameters, viewWidth: Int, viewHeight: Int) = {
    val targetRatio = pms.getPictureSize.width.toDouble / pms.getPictureSize.height.toDouble
    val targetHeight = Math.min(viewHeight, viewWidth)
    val sizes = pms.getSupportedPreviewSizes.asScala.toVector

    def byHeight(s: Camera#Size) = Math.abs(s.height - targetHeight)

    val filteredSizes = sizes.filterNot(s => Math.abs(s.width.toDouble / s.height.toDouble - targetRatio) > CameraPreviewController.ASPECT_TOLERANCE)
    val optimalSize = if (filteredSizes.isEmpty) sizes.minBy(byHeight) else filteredSizes.minBy(byHeight)

    val (w, h) = (optimalSize.width, optimalSize.height)
    PreviewSize(w, h)
  }

  private def setPictureSize(pms: Camera#Parameters, facing: CameraFacing) = {
    val sizes = pms.getSupportedPictureSizes
    val size = if (facing == CameraFacing.FRONT && ("Nexus 4" == Build.MODEL)) sizes.get(1) else sizes.get(0)
    pms.setPictureSize(size.width, size.height)
  }

  /**
    * activityRotation is relative to the natural orientation of the device, regardless of how the device is rotated.
    * That means if your holding the device rotated 90 clockwise, but the activity hasn't rotated because we fixed its
    * orientation, then the activityRotation is still 0, so that the preview is drawn the right way up.
    */
  private def getPreviewOrientation(activityRotation: Int, info: CameraInfo) =
    if (info.cameraFacing == CameraFacing.FRONT) (360 - ((info.fixedOrientation + activityRotation) % 360)) % 360
    else (info.fixedOrientation - activityRotation + 360) % 360

  private def getCameraRotation(deviceRotationDegrees: Int, info: CameraInfo) =
    if (info.cameraFacing == CameraFacing.FRONT) (info.fixedOrientation - deviceRotationDegrees + 360) % 360
    else (info.fixedOrientation + deviceRotationDegrees) % 360

  private def setParams(c: Camera)(f: Camera#Parameters => Unit): Unit = {
    val params = c.getParameters
    f(params)
    c.setParameters(params)
  }
}

//CameraInfo.orientation is fixed for any given device, so we only need to store it once.
private case class CameraInfo(id: Int, cameraFacing: CameraFacing, fixedOrientation: Int)
protected[camera] case class PreviewSize(w: Float, h: Float) {
  def hasSize = w != 0 && h != 0
}

object CameraPreviewController {

  private val FOCUS_MODE_AUTO = null.asInstanceOf[Camera].Parameters.FOCUS_MODE_AUTO
  private val FOCUS_MODE_CONTINUOUS_PICTURE = null.asInstanceOf[Camera].Parameters.FOCUS_MODE_CONTINUOUS_PICTURE

  private val camCoordsRange = 2000;
  private val camCoordsOffset = 1000;
  private val focusWeight = 1000;

  private val CAMERA_THREAD_ID: String = "CAMERA"
  private val ASPECT_TOLERANCE: Double = 0.1
}



