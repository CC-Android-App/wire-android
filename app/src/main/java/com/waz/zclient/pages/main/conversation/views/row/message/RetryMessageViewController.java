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
package com.waz.zclient.pages.main.conversation.views.row.message;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.graphics.RectF;
import android.os.Handler;
import android.support.annotation.CallSuper;
import android.view.View;
import com.waz.api.Message;
import com.waz.api.NetworkMode;
import com.waz.api.UpdateListener;
import com.waz.zclient.R;
import com.waz.zclient.core.stores.network.NetworkAction;
import com.waz.zclient.pages.main.conversation.views.MessageViewsContainer;
import com.waz.zclient.pages.main.conversation.views.row.separator.Separator;
import com.waz.zclient.ui.utils.MathUtils;
import com.waz.zclient.ui.utils.ResourceUtils;
import com.waz.zclient.utils.ViewUtils;
import com.waz.zclient.utils.DateConvertUtils;
import com.waz.zclient.views.FractionalTouchDelegate;
import org.threeten.bp.Duration;
import org.threeten.bp.ZonedDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Warning: your layout needs to contain two views with the ids:
 *      - R.id.v__row_conversation__pending
 *      - R.id.v__row_conversation__error
 *      - R.id.v__row_conversation__e2ee
 */
public abstract class RetryMessageViewController extends MessageViewController implements UpdateListener {

    protected float touchScaleFactor;
    protected int waitingTimeTillPending;

    private View pendingView;
    private View unsentView;
    private Handler handler;
    private Runnable updateRunnable;
    private boolean isRetrying;

    public RetryMessageViewController(Context context, MessageViewsContainer messageViewContainer) {
        super(context, messageViewContainer);
        Resources res = context.getResources();
        touchScaleFactor = ResourceUtils.getResourceFloat(res, R.dimen.content__progress__touch_scaling);
        waitingTimeTillPending = res.getInteger(R.integer.content__progress__time_till_show);
    }

    @Override
    @CallSuper
    protected void afterInit() {
        super.afterInit();
        pendingView = ViewUtils.getView(getView(), R.id.v__row_conversation__pending);
        unsentView = ViewUtils.getView(getView(), R.id.v__row_conversation__error);
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updated();
            }
        };
        handler = new Handler();
    }

    @Override
    @CallSuper
    protected void onSetMessage(Separator separator) {
        updateSentState(message);
        message.addUpdateListener(this);
    }

    @Override
    public void updated() {
        updateSentState(message);
    }

    private void updateSentState(Message message) {
        if (messageViewsContainer == null ||
            messageViewsContainer.getStoreFactory() == null ||
            messageViewsContainer.getStoreFactory().isTornDown()) {
            return;
        }
        Message.Status messageStatus = message.getMessageStatus();
        final ZonedDateTime sentTime = DateConvertUtils.asZonedDateTime(message.getTime());
        final ZonedDateTime now = ZonedDateTime.now();
        final boolean hasInternet = messageViewsContainer.getStoreFactory().getNetworkStore().hasInternetConnection();
        if (messageStatus == Message.Status.PENDING && hasInternet) {
            // We want to show the pending indicator just after we tried sending for more then 3 seconds
            if (Duration.between(sentTime.toLocalDateTime(), now.toLocalDateTime()).getSeconds() >= TimeUnit.SECONDS.convert(waitingTimeTillPending, TimeUnit.MILLISECONDS) || isRetrying) {
                pendingView.setVisibility(View.VISIBLE);
                isRetrying = false;
            } else {
                // Recheck in (3 - alreadyWaitedSeconds)
                long delayMillis = MathUtils.clamp(waitingTimeTillPending - TimeUnit.NANOSECONDS.convert(now.getNano() - sentTime.getNano(), TimeUnit.MILLISECONDS),
                                                   0,
                                                   waitingTimeTillPending);
                handler.postDelayed(updateRunnable, delayMillis);
                pendingView.setVisibility(View.GONE);
            }
            unsentView.setVisibility(View.GONE);
        } else if (messageStatus == Message.Status.FAILED || (!hasInternet && messageStatus == Message.Status.PENDING)) {
            unsentView.setVisibility(View.VISIBLE);
            pendingView.setVisibility(View.GONE);
            initUnsentClick();
        } else {
            handler.removeCallbacks(null);
            pendingView.setVisibility(View.GONE);
            unsentView.setVisibility(View.GONE);
        }

    }

    private void initUnsentClick() {
        RectF sourceFraction = new RectF(touchScaleFactor,
                                         touchScaleFactor,
                                         touchScaleFactor,
                                         touchScaleFactor);
        FractionalTouchDelegate.setupDelegate(getView(), unsentView, sourceFraction);
        unsentView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (messageViewsContainer == null ||
                    messageViewsContainer.getStoreFactory().isTornDown()) {
                    return;
                }

                messageViewsContainer.getStoreFactory().getNetworkStore().doIfHasInternetOrNotifyUser(new NetworkAction() {
                    @Override
                    public void execute(NetworkMode networkMode) {
                        isRetrying = true;
                        message.retry();
                    }

                    @Override
                    public void onNoNetwork() {
                        ViewUtils.showAlertDialog(context,
                                                  R.string.alert_dialog__no_network__header,
                                                  R.string.resend_message__no_network__message,
                                                  R.string.alert_dialog__confirmation,
                                                  new DialogInterface.OnClickListener() {
                                                      @Override
                                                      public void onClick(DialogInterface dialog, int which) {
                                                      }
                                                  }, false);
                    }
                });
            }
        });
    }

    @Override
    @CallSuper
    public void recycle() {
        if (message != null) {
            message.removeUpdateListener(this);
        }
        handler.removeCallbacks(updateRunnable);
        isRetrying = false;
        super.recycle();
    }
}
