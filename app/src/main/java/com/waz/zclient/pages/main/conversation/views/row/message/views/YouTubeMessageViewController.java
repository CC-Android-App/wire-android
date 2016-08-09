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
package com.waz.zclient.pages.main.conversation.views.row.message.views;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import com.waz.api.ImageAsset;
import com.waz.api.LoadHandle;
import com.waz.api.MediaAsset;
import com.waz.api.Message;
import com.waz.api.UpdateListener;
import com.waz.zclient.R;
import com.waz.zclient.core.controllers.tracking.attributes.RangedAttribute;
import com.waz.zclient.core.controllers.tracking.events.media.PlayedYouTubeMessageEvent;
import com.waz.zclient.pages.main.conversation.views.MessageViewsContainer;
import com.waz.zclient.pages.main.conversation.views.row.message.RetryMessageViewController;
import com.waz.zclient.pages.main.conversation.views.row.separator.Separator;
import com.waz.zclient.ui.text.GlyphTextView;
import com.waz.zclient.ui.text.TypefaceTextView;
import com.waz.zclient.ui.utils.ColorUtils;
import com.waz.zclient.ui.utils.ResourceUtils;
import com.waz.zclient.utils.MessageUtils;
import com.waz.zclient.utils.ViewUtils;

import java.util.List;

public class YouTubeMessageViewController extends RetryMessageViewController implements View.OnClickListener,
                                                                                        ImageAsset.BitmapCallback {

    private View view;
    private ImageView imageView;
    private TextMessageWithTimestamp textWithTimestamp;
    private GlyphTextView glyphTextView;
    private TypefaceTextView errorTextView;
    private TypefaceTextView titleTextView;
    private LoadHandle loadHandle;
    private ImageAsset imageAsset;
    private MediaAsset mediaAsset;
    private final float alphaOverlay;
    private final UpdateListener imageAssetUpdateListener = new UpdateListener() {
        @Override
        public void updated() {
            if (view == null ||
                context == null ||
                imageAsset == null) {
                return;
            }
            if (loadHandle != null) {
                loadHandle.cancel();
            }
            loadHandle = imageAsset.getBitmap(getThumbnailWidth(), YouTubeMessageViewController.this);
        }
    };

    @SuppressLint("InflateParams")
    public YouTubeMessageViewController(Context context, MessageViewsContainer messageViewsContainer) {
        super(context, messageViewsContainer);
        LayoutInflater inflater = LayoutInflater.from(context);
        view = inflater.inflate(R.layout.row_conversation_youtube, null);
        textWithTimestamp = ViewUtils.getView(view, R.id.tmwt__message_and_timestamp);
        textWithTimestamp.setMessageViewsContainer(messageViewsContainer);
        imageView = ViewUtils.getView(view, R.id.iv__row_conversation__youtube_image);
        imageView.setOnClickListener(this);
        errorTextView = ViewUtils.getView(view, R.id.ttv__youtube_message__error);
        glyphTextView = ViewUtils.getView(view, R.id.gtv__youtube_message__play);
        titleTextView = ViewUtils.getView(view, R.id.ttv__youtube_message__title);

        alphaOverlay = ResourceUtils.getResourceFloat(context.getResources(), R.dimen.content__youtube__alpha_overlay);
        imageView.getLayoutParams().height = (int) ((double) ViewUtils.getOrientationIndependentDisplayWidth(context) * 9 / 16);
        imageView.setAlpha(0f);

        afterInit();
    }

    @Override
    protected void onSetMessage(Separator separator) {
        super.onSetMessage(separator);
        textWithTimestamp.setMessage(message);
        updated();
    }

    @Override
    public void updated() {
        super.updated();
        if (imageAsset != null) {
            imageAsset.removeUpdateListener(imageAssetUpdateListener);
            imageAsset = null;
        }
        final Message.Part mediaPart = MessageUtils.getFirstRichMediaPart(message);
        if (mediaPart == null) {
            showError();
            return;
        }
        mediaAsset = mediaPart.getMediaAsset();
        if (mediaAsset == null ||
            mediaAsset.isEmpty()) {
            showError();
            return;
        }
        titleTextView.setText(mediaAsset.getTitle());
        imageAsset = mediaAsset.getArtwork();
        imageAsset.addUpdateListener(imageAssetUpdateListener);
        imageAssetUpdateListener.updated();
    }

    @Override
    public View getView() {
        return view;
    }

    @Override
    public void recycle() {
        if (loadHandle != null) {
            loadHandle.cancel();
        }
        if (imageAsset != null) {
            imageAsset.removeUpdateListener(imageAssetUpdateListener);
            imageAsset = null;
        }
        mediaAsset = null;
        textWithTimestamp.recycle();
        imageView.setImageDrawable(null);
        glyphTextView.setText(context.getString(R.string.glyph__play));
        errorTextView.setVisibility(View.GONE);
        imageView.setAlpha(0f);
        imageView.setColorFilter(null);
        titleTextView.setText("");
        super.recycle();
    }

    @Override
    public void onClick(View v) {
        if (mediaAsset == null ||
            messageViewsContainer == null) {
            return;
        }
        mediaAsset.prepareStreaming(new MediaAsset.StreamingCallback() {
            @Override
            public void onSuccess(List<Uri> uris) {
                if (messageViewsContainer == null) {
                    return;
                }
                if (uris.size() == 1) {
                    messageViewsContainer.onOpenUrl(uris.get(0).toString());
                } else {
                    messageViewsContainer.onOpenUrl(mediaAsset.getLinkUri().toString());
                }
            }

            @Override
            public void onFailure(int code, String message, String label) {
                showError();
            }
        });
        messageViewsContainer.getControllerFactory()
                             .getTrackingController()
                             .tagEvent(new PlayedYouTubeMessageEvent(!message.getUser().isMe(),
                                                                     getConversationTypeString()));
        messageViewsContainer.getControllerFactory()
                             .getTrackingController()
                             .updateSessionAggregates(RangedAttribute.YOUTUBE_CONTENT_CLICKS);
    }

    private void showError() {
        titleTextView.setText("");
        if (messageViewsContainer.getStoreFactory().getNetworkStore().hasInternetConnection()) {
            errorTextView.setVisibility(View.VISIBLE);
        }
        glyphTextView.setText(context.getString(R.string.glyph__movie));
        glyphTextView.setTextColor(context.getResources().getColor(R.color.content__youtube__error_indicator__color));
    }

    @Override
    public void onBitmapLoaded(final Bitmap bitmap, boolean isPreview) {
        if (bitmap == null ||
            imageView.getDrawable() != null ||
            isPreview) {
            return;
        }
        errorTextView.setVisibility(View.GONE);
        imageView.setImageBitmap(bitmap);
        imageView.setColorFilter(ColorUtils.injectAlpha(alphaOverlay, Color.BLACK), PorterDuff.Mode.DARKEN);
        ViewUtils.fadeInView(imageView);
    }

    @Override
    public void onBitmapLoadingFailed() {
        showError();
    }

    private int getThumbnailWidth() {
        final int bitmapWidth;
        if (view.getMeasuredWidth() > 0) {
            bitmapWidth = view.getMeasuredWidth();
        } else {
            bitmapWidth = ViewUtils.getOrientationIndependentDisplayWidth(context);
        }
        return bitmapWidth;
    }

}
