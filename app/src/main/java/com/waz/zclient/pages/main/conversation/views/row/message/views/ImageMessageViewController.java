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
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.waz.api.ImageAsset;
import com.waz.api.LoadHandle;
import com.waz.api.UpdateListener;
import com.waz.zclient.R;
import com.waz.zclient.controllers.accentcolor.AccentColorObserver;
import com.waz.zclient.controllers.singleimage.ISingleImageController;
import com.waz.zclient.pages.main.conversation.views.MessageViewsContainer;
import com.waz.zclient.pages.main.conversation.views.row.message.RetryMessageViewController;
import com.waz.zclient.pages.main.conversation.views.row.separator.Separator;
import com.waz.zclient.ui.theme.ThemeUtils;
import com.waz.zclient.ui.views.FilledCircularBackgroundDrawable;
import com.waz.zclient.utils.LayoutSpec;
import com.waz.zclient.utils.ViewUtils;
import com.waz.zclient.views.LoadingIndicatorView;
import timber.log.Timber;

public class ImageMessageViewController extends RetryMessageViewController implements View.OnClickListener,
                                                                                      AccentColorObserver{

    private static final String FULL_IMAGE_LOADED = "FULL_IMAGE_LOADED";

    private View view;
    private FrameLayout errorViewContainer;
    private FrameLayout imageContainer;
    private ImageView gifImageView;
    private ImageView polkadotView;
    private ImageAsset imageAsset;
    private TextView textViewChangeSetting;
    private View wifiContainer;
    private LoadingIndicatorView previewLoadingIndicator;
    private UpdateListener imageAssetUpdateListener;
    private LoadHandle bitmapLoadHandle;
    private boolean previewLoaded;
    private int paddingLeft;
    private int paddingRight;

    @SuppressLint("InflateParams")
    public ImageMessageViewController(Context context, MessageViewsContainer messageViewContainer) {
        super(context, messageViewContainer);
        view = View.inflate(context, R.layout.row_conversation_image, null);
        imageContainer = ViewUtils.getView(view, R.id.fl__row_conversation__message_image_container);
        imageContainer.setOnClickListener(this);
        gifImageView = ViewUtils.getView(view, R.id.iv__row_conversation__message_image);
        polkadotView = ViewUtils.getView(view, R.id.iv__row_conversation__message_polkadots);
        errorViewContainer = ViewUtils.getView(view, R.id.fl__row_conversation__message_error_container);
        previewLoadingIndicator = ViewUtils.getView(view, R.id.lbv__row_conversation__message_polkadots);
        textViewChangeSetting = ViewUtils.getView(view, R.id.ttv__conversation_row__image__change_settings);
        wifiContainer = ViewUtils.getView(view, R.id.ll__conversation_row__image__wifi_warning);
        wifiContainer.setVisibility(View.GONE);
        TextView unsentView = ViewUtils.getView(view, R.id.v__row_conversation__error);
        final int circleFillColor = ThemeUtils.isDarkTheme(context) ? context.getResources().getColor(R.color.content__image__progress_circle_background_dark)
                                                                    : context.getResources().getColor(R.color.content__image__progress_circle_background_light);
        final int circleRadius = context.getResources().getDimensionPixelSize(R.dimen.content__message__unsend_indicator_background_radius);
        final int circleDiameter = 2 * circleRadius;
        unsentView.setBackground(new FilledCircularBackgroundDrawable(circleFillColor, circleDiameter));

        previewLoadingIndicator.setColor(messageViewContainer.getControllerFactory().getAccentColorController().getColor());
        previewLoadingIndicator.setType(LoadingIndicatorView.INFINITE_LOADING_BAR);
        previewLoadingIndicator.setVisibility(View.GONE);

        paddingLeft = (int) context.getResources().getDimension(R.dimen.content__padding_left);
        paddingRight = (int) context.getResources().getDimension(R.dimen.content__padding_right);

        afterInit();
    }

    @Override
    public void onSetMessage(Separator separator) {
        super.onSetMessage(separator);
        wifiContainer.setVisibility(View.GONE);
        gifImageView.setTag(message.getId());
        imageAsset = message.getImage();

        int displayWidth;
        if (ViewUtils.isInPortrait(context)) {
            displayWidth = ViewUtils.getOrientationIndependentDisplayWidth(context);
        } else {
            displayWidth = ViewUtils.getOrientationIndependentDisplayHeight(context) - context.getResources().getDimensionPixelSize(R.dimen.framework__sidebar_width);
        }
        int originalWidth = ViewUtils.toPx(context, message.getImageWidth());
        int originalHeight = ViewUtils.toPx(context, message.getImageHeight());

        // no left/right padding for full width images
        boolean imageViewSidePadding = originalWidth < displayWidth;
        if (!imageViewSidePadding) {
            ViewUtils.setPaddingLeftRight(imageContainer, 0);
        } else {
            if (LayoutSpec.isTablet(imageContainer.getContext()) &&
                ViewUtils.isInPortrait(imageContainer.getContext())) {
                ViewUtils.setPaddingLeft(imageContainer, 0);
                ViewUtils.setPaddingRight(imageContainer, 0);
                ViewUtils.setWidth(imageContainer, imageContainer.getContext().getResources().getDimensionPixelSize(R.dimen.content__width));
            } else {
                ViewUtils.setPaddingLeft(imageContainer, paddingLeft);
                ViewUtils.setPaddingRight(imageContainer, paddingRight);
                imageContainer.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                                                                            FrameLayout.LayoutParams.MATCH_PARENT));
            }
        }

        final int finalWidth = computeFinalWidth(originalWidth, displayWidth, imageViewSidePadding);
        final int finalHeight = getScaledHeight(originalWidth, originalHeight, finalWidth);

        ViewGroup.LayoutParams layoutParams = gifImageView.getLayoutParams();
        layoutParams.width = finalWidth;
        layoutParams.height = finalHeight;
        gifImageView.setLayoutParams(layoutParams);
        polkadotView.setLayoutParams(layoutParams);
        ViewUtils.setWidth(previewLoadingIndicator, finalWidth);


        if (imageAsset == null) {
            Timber.e("No imageAsset for message with id='%s' available.", message.getId());
            return;
        }

        imageAssetUpdateListener = new UpdateListener() {
            @Override
            public void updated() {
                loadBitmap(finalWidth);
            }
        };
        imageAsset.addUpdateListener(imageAssetUpdateListener);
        loadBitmap(finalWidth);

        messageViewsContainer.getControllerFactory().getAccentColorController().addAccentColorObserver(this);
    }

    private void loadBitmap(int finalViewWidth) {
        previewLoaded = false;

        if (bitmapLoadHandle != null) {
            bitmapLoadHandle.cancel();
        }

        bitmapLoadHandle = imageAsset.getBitmap(finalViewWidth, new ImageAsset.BitmapCallback() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap, boolean isPreview) {
                if ((previewLoaded || gifImageView.getDrawable() != null) && isPreview) {
                    return;
                }

                if (gifImageView == null ||
                    message == null ||
                    !gifImageView.getTag().equals(message.getId()) ||
                    messageViewsContainer.isTornDown()) {
                    return;
                }

                if (isPreview) {
                    showPreview(bitmap);

                    boolean hasWifi = messageViewsContainer.getStoreFactory().getNetworkStore().hasWifiConnection();
                    boolean downloadPolicyWifiOnly = messageViewsContainer.getControllerFactory().getUserPreferencesController().isImageDownloadPolicyWifiOnly();

                    if (!hasWifi && downloadPolicyWifiOnly) {
                        wifiContainer.setVisibility(View.VISIBLE);
                        textViewChangeSetting.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (!messageViewsContainer.isTornDown()) {
                                    messageViewsContainer.openSettings();
                                }
                            }
                        });
 
                        previewLoadingIndicator.setVisibility(View.GONE);
                        previewLoadingIndicator.hide();
                    }
                } else {
                    view.setTag(FULL_IMAGE_LOADED);
                    if (gifImageView.getDrawable() != null) {
                        gifImageView.setImageBitmap(bitmap);
                    } else {
                        showFinalImage(bitmap);
                    }
                }
            }

            @Override public void onBitmapLoadingFailed() { }
        });
    }

    private void showPreview(final Bitmap bitmap) {
        previewLoaded = true;
        polkadotView.setAlpha(1f);
        polkadotView.setVisibility(View.VISIBLE);
        polkadotView.setImageBitmap(bitmap);
        gifImageView.setVisibility(View.GONE);
        previewLoadingIndicator.setVisibility(View.VISIBLE);
        previewLoadingIndicator.show();
    }

    private void showFinalImage(final Bitmap bitmap) {
        gifImageView.setImageBitmap(bitmap);
        gifImageView.setAlpha(0f);
        gifImageView.setVisibility(View.VISIBLE);
        previewLoadingIndicator.hide();
        previewLoadingIndicator.setVisibility(View.GONE);

        int crossFadeDuration = context.getResources().getInteger(R.integer.content__image__polka_crossfade_duration);
        int showFinalDirectlyDuration = context.getResources().getInteger(R.integer.content__image__directly_final_duration);
        int fadingDuration = previewLoaded ? crossFadeDuration : showFinalDirectlyDuration;
        int polkaShowDuration = context.getResources().getInteger(R.integer.content__image__polka_show_duration);
        int startDelay = previewLoaded ? polkaShowDuration : 0;

        gifImageView.animate()
                    .alpha(1f)
                    .setDuration(fadingDuration)
                    .setStartDelay(startDelay)
                    .start();

        polkadotView.animate()
                    .alpha(0f)
                    .setDuration(fadingDuration)
                    .setStartDelay(startDelay)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            polkadotView.setVisibility(View.GONE);
                        }
                    })
                    .start();

    }

    private int computeFinalWidth(int originalWidth, int displayWidth, boolean imageViewSidePadding) {
        if (imageViewSidePadding) {
            return Math.min(originalWidth, displayWidth - paddingLeft - paddingRight);
        } else {
            return displayWidth;
        }
    }

    private int getScaledHeight(int originalWidth, int originalHeight, double finalWidth) {
        double scaleFactor = finalWidth / originalWidth;
        return (int) (originalHeight * scaleFactor);
    }

    @Override
    public View getView() {
        return view;
    }

    @Override
    public void recycle() {
        if (!messageViewsContainer.isTornDown()) {
            messageViewsContainer.getControllerFactory().getAccentColorController().removeAccentColorObserver(this);
        }
        errorViewContainer.clearAnimation();
        errorViewContainer.setVisibility(View.VISIBLE);
        gifImageView.animate().cancel();
        polkadotView.animate().cancel();
        gifImageView.setVisibility(View.INVISIBLE);
        gifImageView.setImageDrawable(null);
        previewLoadingIndicator.hide();
        previewLoadingIndicator.setVisibility(View.GONE);
        textViewChangeSetting.setOnClickListener(null);
        wifiContainer.setVisibility(View.GONE);
        previewLoaded = false;
        view.setTag(null);
        if (imageAsset != null) {
            imageAsset.removeUpdateListener(imageAssetUpdateListener);
        }

        if (bitmapLoadHandle != null) {
            bitmapLoadHandle.cancel();
            bitmapLoadHandle = null;
        }
        polkadotView.setImageBitmap(null);
        super.recycle();
    }

    @Override
    public void onAccentColorHasChanged(Object sender, int color) {
        if (previewLoadingIndicator != null) {
            previewLoadingIndicator.setColor(color);
        }
    }

    @Override
    public void onClick(View v) {
        boolean fullImageLoaded = view != null && ImageMessageViewController.FULL_IMAGE_LOADED.equals(view.getTag());
        if (!fullImageLoaded) {
            return;
        }
        View clickedImageSendingIndicator = errorViewContainer;
        if (clickedImageSendingIndicator != null && clickedImageSendingIndicator.getVisibility() == View.GONE) {
            clickedImageSendingIndicator = null;
        }
        final ISingleImageController singleImageController = messageViewsContainer.getControllerFactory().getSingleImageController();
        singleImageController.setViewReferences(gifImageView, clickedImageSendingIndicator);
        singleImageController.showSingleImage(message);
    }

}
