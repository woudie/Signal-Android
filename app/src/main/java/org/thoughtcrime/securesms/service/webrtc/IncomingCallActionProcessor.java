package org.thoughtcrime.securesms.service.webrtc;

import android.net.Uri;
import android.os.ResultReceiver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.ringrtc.CallException;
import org.signal.ringrtc.CallId;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.events.CallParticipant;
import org.thoughtcrime.securesms.events.WebRtcViewModel;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.notifications.DoNotDisturbUtil;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.ringrtc.CallState;
import org.thoughtcrime.securesms.ringrtc.IceCandidateParcel;
import org.thoughtcrime.securesms.ringrtc.RemotePeer;
import org.thoughtcrime.securesms.service.webrtc.state.VideoState;
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceState;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.webrtc.locks.LockManager;
import org.webrtc.PeerConnection;
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.TYPE_INCOMING_RINGING;

/**
 * Responsible for setting up and managing the start of an incoming 1:1 call. Transitioned
 * to from idle or pre-join and can either move to a connected state (user picks up) or
 * a disconnected state (remote hangup, local hangup, etc.).
 */
public class IncomingCallActionProcessor extends DeviceAwareActionProcessor {

  private static final String TAG = Log.tag(IncomingCallActionProcessor.class);

  private final ActiveCallActionProcessorDelegate activeCallDelegate;
  private final CallSetupActionProcessorDelegate  callSetupDelegate;

  public IncomingCallActionProcessor(@NonNull WebRtcInteractor webRtcInteractor) {
    super(webRtcInteractor, TAG);
    activeCallDelegate = new ActiveCallActionProcessorDelegate(webRtcInteractor, TAG);
    callSetupDelegate  = new CallSetupActionProcessorDelegate(webRtcInteractor, TAG);
  }

  @Override
  protected @NonNull WebRtcServiceState handleIsInCallQuery(@NonNull WebRtcServiceState currentState, @Nullable ResultReceiver resultReceiver) {
    return activeCallDelegate.handleIsInCallQuery(currentState, resultReceiver);
  }

  @Override
  protected @NonNull WebRtcServiceState handleSendAnswer(@NonNull WebRtcServiceState currentState,
                                                         @NonNull WebRtcData.CallMetadata callMetadata,
                                                         @NonNull WebRtcData.AnswerMetadata answerMetadata,
                                                         boolean broadcast)
  {
    Log.i(TAG, "handleSendAnswer(): id: " + callMetadata.getCallId().format(callMetadata.getRemoteDevice()));

    AnswerMessage            answerMessage       = new AnswerMessage(callMetadata.getCallId().longValue(), answerMetadata.getSdp(), answerMetadata.getOpaque());
    Integer                  destinationDeviceId = broadcast ? null : callMetadata.getRemoteDevice();
    SignalServiceCallMessage callMessage         = SignalServiceCallMessage.forAnswer(answerMessage, true, destinationDeviceId);

    webRtcInteractor.sendCallMessage(callMetadata.getRemotePeer(), callMessage);

    return currentState;
  }

  @Override
  public @NonNull WebRtcServiceState handleTurnServerUpdate(@NonNull WebRtcServiceState currentState,
                                                            @NonNull List<PeerConnection.IceServer> iceServers,
                                                            boolean isAlwaysTurn)
  {
    RemotePeer      activePeer      = currentState.getCallInfoState().requireActivePeer();
    boolean         hideIp          = !activePeer.getRecipient().isSystemContact() || isAlwaysTurn;
    VideoState      videoState      = currentState.getVideoState();
    CallParticipant callParticipant = Objects.requireNonNull(currentState.getCallInfoState().getRemoteCallParticipant(activePeer.getRecipient()));

    try {
      webRtcInteractor.getCallManager().proceed(activePeer.getCallId(),
                                                context,
                                                videoState.requireEglBase(),
                                                videoState.requireLocalSink(),
                                                callParticipant.getVideoSink(),
                                                videoState.requireCamera(),
                                                iceServers,
                                                hideIp,
                                                false);
    } catch (CallException e) {
      return callFailure(currentState, "Unable to proceed with call: ", e);
    }

    webRtcInteractor.updatePhoneState(LockManager.PhoneState.PROCESSING);
    webRtcInteractor.sendMessage(currentState);

    return currentState;
  }

  @Override
  protected @NonNull WebRtcServiceState handleAcceptCall(@NonNull WebRtcServiceState currentState, boolean answerWithVideo) {
    RemotePeer activePeer = currentState.getCallInfoState().requireActivePeer();

    Log.i(TAG, "handleAcceptCall(): call_id: " + activePeer.getCallId());

    DatabaseFactory.getSmsDatabase(context).insertReceivedCall(activePeer.getId(), currentState.getCallSetupState().isRemoteVideoOffer());

    currentState = currentState.builder()
                               .changeCallSetupState()
                               .acceptWithVideo(answerWithVideo)
                               .build();

    try {
      webRtcInteractor.getCallManager().acceptCall(activePeer.getCallId());
    } catch (CallException e) {
      return callFailure(currentState, "accept() failed: ", e);
    }

    return currentState;
  }

  protected @NonNull WebRtcServiceState handleDenyCall(@NonNull WebRtcServiceState currentState) {
    RemotePeer activePeer = currentState.getCallInfoState().requireActivePeer();

    if (activePeer.getState() != CallState.LOCAL_RINGING) {
      Log.w(TAG, "Can only deny from ringing!");
      return currentState;
    }

    Log.i(TAG, "handleDenyCall():");

    try {
      webRtcInteractor.getCallManager().hangup();
      DatabaseFactory.getSmsDatabase(context).insertMissedCall(activePeer.getId(), System.currentTimeMillis(), currentState.getCallSetupState().isRemoteVideoOffer());
      return terminate(currentState, activePeer);
    } catch  (CallException e) {
      return callFailure(currentState, "hangup() failed: ", e);
    }
  }

  protected @NonNull WebRtcServiceState handleLocalRinging(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    Log.i(TAG, "handleLocalRinging(): call_id: " + remotePeer.getCallId());

    RemotePeer activePeer = currentState.getCallInfoState().requireActivePeer();
    Recipient  recipient  = remotePeer.getRecipient();

    activePeer.localRinging();
    webRtcInteractor.updatePhoneState(LockManager.PhoneState.INTERACTIVE);

    boolean shouldDisturbUserWithCall = DoNotDisturbUtil.shouldDisturbUserWithCall(context.getApplicationContext(), recipient);
    if (shouldDisturbUserWithCall) {
      webRtcInteractor.startWebRtcCallActivityIfPossible();
    }

    webRtcInteractor.initializeAudioForCall();
    if (shouldDisturbUserWithCall && TextSecurePreferences.isCallNotificationsEnabled(context)) {
      Uri                            ringtone     = recipient.resolve().getCallRingtone();
      RecipientDatabase.VibrateState vibrateState = recipient.resolve().getCallVibrate();

      if (ringtone == null) {
        ringtone = TextSecurePreferences.getCallNotificationRingtone(context);
      }

      webRtcInteractor.startIncomingRinger(ringtone, vibrateState == RecipientDatabase.VibrateState.ENABLED || (vibrateState == RecipientDatabase.VibrateState.DEFAULT && TextSecurePreferences.isCallNotificationVibrateEnabled(context)));
    }

    webRtcInteractor.registerPowerButtonReceiver();
    webRtcInteractor.setCallInProgressNotification(TYPE_INCOMING_RINGING, activePeer);

    return currentState.builder()
                       .changeCallInfoState()
                       .callState(WebRtcViewModel.State.CALL_INCOMING)
                       .build();
  }

  protected @NonNull WebRtcServiceState handleScreenOffChange(@NonNull WebRtcServiceState currentState) {
    Log.i(TAG, "Silencing incoming ringer...");

    webRtcInteractor.silenceIncomingRinger();
    return currentState;
  }

  @Override
  protected @NonNull  WebRtcServiceState handleRemoteVideoEnable(@NonNull WebRtcServiceState currentState, boolean enable) {
    return activeCallDelegate.handleRemoteVideoEnable(currentState, enable);
  }

  @Override
  protected @NonNull WebRtcServiceState handleReceivedOfferWhileActive(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    return activeCallDelegate.handleReceivedOfferWhileActive(currentState, remotePeer);
  }

  @Override
  protected @NonNull WebRtcServiceState handleEndedRemote(@NonNull WebRtcServiceState currentState, @NonNull String action, @NonNull RemotePeer remotePeer) {
    return activeCallDelegate.handleEndedRemote(currentState, action, remotePeer);
  }

  @Override
  protected @NonNull WebRtcServiceState handleEnded(@NonNull WebRtcServiceState currentState, @NonNull String action, @NonNull RemotePeer remotePeer) {
    return activeCallDelegate.handleEnded(currentState, action, remotePeer);
  }

  @Override
  protected  @NonNull WebRtcServiceState handleSetupFailure(@NonNull WebRtcServiceState currentState, @NonNull CallId callId) {
    return activeCallDelegate.handleSetupFailure(currentState, callId);
  }

  @Override
  protected @NonNull WebRtcServiceState handleCallConcluded(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    return activeCallDelegate.handleCallConcluded(currentState, remotePeer);
  }

  @Override
  protected @NonNull WebRtcServiceState handleSendIceCandidates(@NonNull WebRtcServiceState currentState,
                                                                @NonNull WebRtcData.CallMetadata callMetadata,
                                                                boolean broadcast,
                                                                @NonNull ArrayList<IceCandidateParcel> iceCandidates)
  {
    return activeCallDelegate.handleSendIceCandidates(currentState, callMetadata, broadcast, iceCandidates);
  }

  @Override
  protected @NonNull WebRtcServiceState handleReceivedIceCandidates(@NonNull WebRtcServiceState currentState,
                                                                    @NonNull WebRtcData.CallMetadata callMetadata,
                                                                    @NonNull ArrayList<IceCandidateParcel> iceCandidateParcels)
  {
    return activeCallDelegate.handleReceivedIceCandidates(currentState, callMetadata, iceCandidateParcels);
  }

  @Override
  public @NonNull WebRtcServiceState handleCallConnected(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    return callSetupDelegate.handleCallConnected(currentState, remotePeer);
  }

  @Override
  protected @NonNull WebRtcServiceState handleSetEnableVideo(@NonNull WebRtcServiceState currentState, boolean enable) {
    return callSetupDelegate.handleSetEnableVideo(currentState, enable);
  }
}
