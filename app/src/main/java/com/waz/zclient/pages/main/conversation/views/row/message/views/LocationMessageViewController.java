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

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.waz.api.ImageAsset;
import com.waz.api.LoadHandle;
import com.waz.api.Message;
import com.waz.api.MessageContent;
import com.waz.zclient.R;
import com.waz.zclient.controllers.accentcolor.AccentColorObserver;
import com.waz.zclient.core.api.scala.ModelObserver;
import com.waz.zclient.core.controllers.tracking.events.media.OpenedSharedLocationEvent;
import com.waz.zclient.pages.main.conversation.views.MessageViewsContainer;
import com.waz.zclient.pages.main.conversation.views.row.message.RetryMessageViewController;
import com.waz.zclient.pages.main.conversation.views.row.separator.Separator;
import com.waz.zclient.ui.text.GlyphTextView;
import com.waz.zclient.ui.theme.ThemeUtils;
import com.waz.zclient.ui.views.FilledCircularBackgroundDrawable;
import com.waz.zclient.utils.IntentUtils;
import com.waz.zclient.utils.LayoutSpec;
import com.waz.zclient.utils.StringUtils;
import com.waz.zclient.utils.ViewUtils;
import timber.log.Timber;

public class LocationMessageViewController extends RetryMessageViewController implements OnClickListener,
                                                                                         AccentColorObserver {

    private static final String TAG = LocationMessageViewController.class.getName();
    private static final String FULL_IMAGE_LOADED = "FULL_IMAGE_LOADED";

    private View view;
    private FrameLayout errorViewContainer;
    private FrameLayout imageContainer;
    private ImageView mapImageView;
    private TextView locationName;

    private ImageAsset imageAsset;
    private LoadHandle bitmapLoadHandle;
    private GlyphTextView pinView;
    private View pinImage;
    private TextView mapPlaceholderText;

    private int imageWidth;

    private ModelObserver<ImageAsset> imageAssetModelObserver = new ModelObserver<ImageAsset>() {
        @Override
        public void updated(ImageAsset model) {
            if (context == null) {
                return;
            }
            loadBitmap(imageWidth);
        }
    };

    private ModelObserver<Message> messageModelObserver = new ModelObserver<Message>() {
        @Override
        public void updated(Message model) {
            mapImageView.setTag(message.getId());
            if (imageWidth > 0) {
                imageAsset = message.getImage(imageWidth, context.getResources().getDimensionPixelSize(R.dimen.content__location_message__map_height));
            } else {
                imageAsset = message.getImage();
            }
            if (imageAsset == null) {
                Timber.e("No imageAsset for message with id='%s' available.", message.getId());
                return;
            }
            if (imageAsset.isEmpty()) {
                Timber.i("ImageAsset for message with id='%s' is empty.", message.getId());
            }
            imageAssetModelObserver.addAndUpdate(imageAsset);
        }
    };

    public LocationMessageViewController(Context context, MessageViewsContainer messageViewContainer) {
        super(context, messageViewContainer);
        view = View.inflate(context, R.layout.row_conversation_location, null);

        errorViewContainer = ViewUtils.getView(view, R.id.fl__row_conversation__message_error_container);
        imageContainer = ViewUtils.getView(view, R.id.fl__row_conversation__map_image_container);
        imageContainer.setOnClickListener(this);
        mapImageView = ViewUtils.getView(view, R.id.biv__row_conversation__map_image);
        locationName = ViewUtils.getView(view, R.id.ttv__row_conversation_map_name);
        mapPlaceholderText = ViewUtils.getView(view, R.id.ttv__row_conversation_map_image_placeholder_text);
        pinView = ViewUtils.getView(view, R.id.gtv__row_conversation__map_pin_glyph);
        pinImage = ViewUtils.getView(view, R.id.iv__row_conversation__map_pin_image);
        pinView.setTextColor(ContextCompat.getColor(context, R.color.accent_blue));
        TextView unsentView = ViewUtils.getView(view, R.id.v__row_conversation__error);
        final int circleFillColor = ThemeUtils.isDarkTheme(context) ? context.getResources().getColor(R.color.content__image__progress_circle_background_dark)
                                                                    : context.getResources().getColor(R.color.content__image__progress_circle_background_light);
        final int circleRadius = context.getResources().getDimensionPixelSize(R.dimen.content__message__unsend_indicator_background_radius);
        final int circleDiameter = 2 * circleRadius;
        unsentView.setBackground(new FilledCircularBackgroundDrawable(circleFillColor, circleDiameter));
        unsentView.requestLayout();

        imageWidth = getImageWidth();
        afterInit();
    }

    @Override
    public View getView() {
        return view;
    }

    @Override
    protected void onSetMessage(Separator separator) {
        super.onSetMessage(separator);
        messageModelObserver.addAndUpdate(message);
        messageViewsContainer.getControllerFactory().getAccentColorController().addAccentColorObserver(this);

        MessageContent.Location location = message.getLocation();
        if (StringUtils.isBlank(location.getName())) {
            locationName.setVisibility(View.GONE);
        } else {
            locationName.setText(location.getName());
            locationName.setVisibility(View.VISIBLE);
        }
    }

    private void loadBitmap(int finalViewWidth) {
        if (bitmapLoadHandle != null) {
            bitmapLoadHandle.cancel();
        }

        bitmapLoadHandle = imageAsset.getBitmap(finalViewWidth, new ImageAsset.BitmapCallback() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap, boolean isPreview) {
                if (isPreview ||
                    mapImageView == null ||
                    message == null ||
                    !mapImageView.getTag().equals(message.getId()) ||
                    messageViewsContainer.isTornDown()) {
                    return;
                }

                pinImage.setVisibility(View.VISIBLE);
                pinView.setVisibility(View.VISIBLE);
                mapPlaceholderText.setVisibility(View.GONE);
                view.setTag(FULL_IMAGE_LOADED);
                if (mapImageView.getDrawable() != null) {
                    mapImageView.setImageBitmap(bitmap);
                } else {
                    showFinalImage(bitmap);
                }
            }

            @Override public void onBitmapLoadingFailed() { }
        });
    }

    private void showFinalImage(final Bitmap bitmap) {
        mapImageView.setImageBitmap(bitmap);
        mapImageView.setAlpha(0f);
        mapImageView.setVisibility(View.VISIBLE);
        int showFinalDirectlyDuration = context.getResources().getInteger(R.integer.content__image__directly_final_duration);
        mapImageView.animate()
                    .alpha(1f)
                    .setDuration(showFinalDirectlyDuration)
                    .start();
    }

    private int getDisplayWidth() {
        if (ViewUtils.isInPortrait(context)) {
            return ViewUtils.getOrientationIndependentDisplayWidth(context);
        } else {
            return ViewUtils.getOrientationIndependentDisplayHeight(context) - context.getResources().getDimensionPixelSize(R.dimen.framework__sidebar_width);
        }
    }

    private int getImageWidth() {
        if (LayoutSpec.isTablet(context) && ViewUtils.isInPortrait(context)) {
            return context.getResources().getDimensionPixelSize(R.dimen.content__width);
        }
        return  getDisplayWidth() -
                context.getResources().getDimensionPixelSize(R.dimen.content__padding_left) -
                context.getResources().getDimensionPixelSize(R.dimen.content__padding_right);
    }

    @Override
    public void recycle() {
        if (!messageViewsContainer.isTornDown()) {
            messageViewsContainer.getControllerFactory().getAccentColorController().removeAccentColorObserver(this);
        }

        errorViewContainer.clearAnimation();
        errorViewContainer.setVisibility(View.VISIBLE);
        mapImageView.animate().cancel();
        mapImageView.setVisibility(View.INVISIBLE);
        mapImageView.setImageDrawable(null);
        pinImage.setVisibility(View.INVISIBLE);
        pinView.setVisibility(View.INVISIBLE);
        mapPlaceholderText.setVisibility(View.VISIBLE);

        locationName.setText("");
        view.setTag(null);
        imageAssetModelObserver.clear();
        messageModelObserver.clear();

        if (bitmapLoadHandle != null) {
            bitmapLoadHandle.cancel();
            bitmapLoadHandle = null;
        }
        super.recycle();
    }

    @Override
    public void onClick(View v) {
        MessageContent.Location location = message.getLocation();
        Intent intent = IntentUtils.getGoogleMapsIntent(context, location.getLatitude(), location.getLongitude(), location.getZoom(), location.getName());
        if (intent == null) {
            return;
        }
        messageViewsContainer.getControllerFactory().getTrackingController().tagEvent(new OpenedSharedLocationEvent(
            getConversationTypeString(),
            !message.getUser().isMe()));
        context.startActivity(intent);
    }

    @Override
    public void onAccentColorHasChanged(Object sender, int color) {
        pinView.setTextColor(color);
    }
}
