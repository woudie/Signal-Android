package org.thoughtcrime.securesms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.InputType;
import android.text.TextUtils;
import android.transition.TransitionInflater;
import android.util.DisplayMetrics;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.autofill.HintConstants;
import androidx.core.app.DialogCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.preference.Preference;

import org.thoughtcrime.securesms.components.SwitchPreferenceCompat;
import org.thoughtcrime.securesms.contactshare.SimpleTextWatcher;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.keyvalue.PinValues;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.lock.PinHashing;
import org.thoughtcrime.securesms.lock.v2.KbsConstants;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.pin.PinRestoreActivity;
import org.thoughtcrime.securesms.qr.ScanListener;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.DynamicLanguage;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.ServiceUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.ThemeUtil;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.exceptions.NotFoundException;
import org.whispersystems.signalservice.internal.push.DeviceLimitExceededException;
import org.thoughtcrime.securesms.lock.v2.CreateKbsPinActivity;

import java.io.IOException;
import java.util.Objects;

public class DeviceActivity extends PassphraseRequiredActivity
        implements Button.OnClickListener, ScanListener, DeviceLinkFragment.LinkClickedListener
{
  private static final String TAG = DeviceActivity.class.getSimpleName();

  private final DynamicTheme    dynamicTheme    = new DynamicTheme();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private DeviceAddFragment  deviceAddFragment;
  private DeviceListFragment deviceListFragment;
  private DeviceLinkFragment deviceLinkFragment;

  @Override
  public void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    getSupportActionBar().setHomeAsUpIndicator(ContextCompat.getDrawable(this, R.drawable.ic_arrow_left_24));
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    getSupportActionBar().setTitle(R.string.AndroidManifest__linked_devices);
    this.deviceAddFragment  = new DeviceAddFragment();
    this.deviceListFragment = new DeviceListFragment();
    this.deviceLinkFragment = new DeviceLinkFragment();

    this.deviceListFragment.setAddDeviceButtonListener(this);
    this.deviceAddFragment.setScanListener(this);

    if (getIntent().getBooleanExtra("add", false)) {
      initFragment(android.R.id.content, deviceAddFragment, dynamicLanguage.getCurrentLocale());
    } else {
      initFragment(android.R.id.content, deviceListFragment, dynamicLanguage.getCurrentLocale());
    }

    overridePendingTransition(R.anim.slide_from_end, R.anim.slide_to_start);
  }

  @Override
  protected void onPause() {
    if (isFinishing()) {
      overridePendingTransition(R.anim.slide_from_start, R.anim.slide_to_end);
    }
    super.onPause();
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case android.R.id.home: finish(); return true;
    }

    return false;
  }

  private Intent getRoutedIntent(Class<?> destination, @Nullable Intent nextIntent) {
    final Intent intent = new Intent(this, destination);
    if (nextIntent != null)   intent.putExtra("next_intent", nextIntent);
    return intent;
  }

  private final Intent getPromptPassphraseIntent() {
    return getRoutedIntent(PassphrasePromptActivity.class, getIntent());
  }


  @Override
  public void onClick(View v) {

    DisplayMetrics metrics = this.getResources().getDisplayMetrics();
    AlertDialog dialog  = new AlertDialog.Builder(this, ThemeUtil.isDarkTheme(this) ? R.style.Theme_Signal_AlertDialog_Dark_Cornered_ColoredAccent : R.style.Theme_Signal_AlertDialog_Light_Cornered_ColoredAccent)
            .setView(R.layout.device_add_dialog)
            .create();

    dialog.show();
    dialog.getWindow().setLayout((int)(metrics.widthPixels * .80), ViewGroup.LayoutParams.WRAP_CONTENT);

    EditText pinEditText   = (EditText) DialogCompat.requireViewById(dialog, R.id.reminder_disable_pin);
    TextView statusText    = (TextView) DialogCompat.requireViewById(dialog, R.id.reminder_disable_status);
    View     cancelButton  = DialogCompat.requireViewById(dialog, R.id.reminder_disable_cancel);
    View     turnOffButton = DialogCompat.requireViewById(dialog, R.id.reminder_disable_turn_off);

    pinEditText.post(() -> {
      if (pinEditText.requestFocus()) {
        ServiceUtil.getInputMethodManager(pinEditText.getContext()).showSoftInput(pinEditText, 0);
      }
    });

    ViewCompat.setAutofillHints(pinEditText, HintConstants.AUTOFILL_HINT_PASSWORD);

    switch (SignalStore.pinValues().getKeyboardType()) {
      case NUMERIC:
        pinEditText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        break;
      case ALPHA_NUMERIC:
        pinEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        break;
      default:
        throw new AssertionError("Unexpected type!");
    }

    pinEditText.addTextChangedListener(new SimpleTextWatcher() {
      @Override
      public void onTextChanged(String text) {
        turnOffButton.setEnabled(text.length() >= KbsConstants.MINIMUM_PIN_LENGTH);
      }
    });

    pinEditText.setTypeface(Typeface.DEFAULT);

    turnOffButton.setOnClickListener(nV -> {
      String  pin     = pinEditText.getText().toString();
      boolean correct = PinHashing.verifyLocalPinHash(Objects.requireNonNull(SignalStore.kbsValues().getLocalPinHash()), pin);

      if (correct) {
        Permissions.with(this)
                .request(Manifest.permission.CAMERA)
                .ifNecessary()
                .withPermanentDenialDialog(getString(R.string.DeviceActivity_signal_needs_the_camera_permission_in_order_to_scan_a_qr_code))
                .onAllGranted(() -> {
                  getSupportFragmentManager().beginTransaction()
                          .replace(android.R.id.content, deviceAddFragment)
                          .addToBackStack(null)
                          .commitAllowingStateLoss();
                })
                .onAnyDenied(() -> Toast.makeText(this, R.string.DeviceActivity_unable_to_scan_a_qr_code_without_the_camera_permission, Toast.LENGTH_LONG).show())
                .execute();
        dialog.dismiss();
      } else {

        statusText.setText(R.string.preferences_app_protection__incorrect_pin_try_again);
      }
    });

    cancelButton.setOnClickListener(nV -> dialog.dismiss());

  }

  @Override
  public void onQrDataFound(final String data) {
    Util.runOnMain(() -> {
      ((Vibrator)getSystemService(Context.VIBRATOR_SERVICE)).vibrate(50);
      Uri uri = Uri.parse(data);
      deviceLinkFragment.setLinkClickedListener(uri, DeviceActivity.this);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        deviceAddFragment.setSharedElementReturnTransition(TransitionInflater.from(DeviceActivity.this).inflateTransition(R.transition.fragment_shared));
        deviceAddFragment.setExitTransition(TransitionInflater.from(DeviceActivity.this).inflateTransition(android.R.transition.fade));

        deviceLinkFragment.setSharedElementEnterTransition(TransitionInflater.from(DeviceActivity.this).inflateTransition(R.transition.fragment_shared));
        deviceLinkFragment.setEnterTransition(TransitionInflater.from(DeviceActivity.this).inflateTransition(android.R.transition.fade));

        getSupportFragmentManager().beginTransaction()
                .addToBackStack(null)
                .addSharedElement(deviceAddFragment.getDevicesImage(), "devices")
                .replace(android.R.id.content, deviceLinkFragment)
                .commit();

      } else {
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.slide_from_bottom, R.anim.slide_to_bottom,
                        R.anim.slide_from_bottom, R.anim.slide_to_bottom)
                .replace(android.R.id.content, deviceLinkFragment)
                .addToBackStack(null)
                .commit();
      }
    });
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @SuppressLint("StaticFieldLeak")
  @Override
  public void onLink(final Uri uri) {
    new ProgressDialogAsyncTask<Void, Void, Integer>(this,
            R.string.DeviceProvisioningActivity_content_progress_title,
            R.string.DeviceProvisioningActivity_content_progress_content)
    {
      private static final int SUCCESS        = 0;
      private static final int NO_DEVICE      = 1;
      private static final int NETWORK_ERROR  = 2;
      private static final int KEY_ERROR      = 3;
      private static final int LIMIT_EXCEEDED = 4;
      private static final int BAD_CODE       = 5;

      @Override
      protected Integer doInBackground(Void... params) {
        boolean isMultiDevice = TextSecurePreferences.isMultiDevice(DeviceActivity.this);

        try {
          Context                     context          = DeviceActivity.this;
          SignalServiceAccountManager accountManager   = ApplicationDependencies.getSignalServiceAccountManager();
          String                      verificationCode = accountManager.getNewDeviceVerificationCode();
          String                      ephemeralId      = uri.getQueryParameter("uuid");
          String                      publicKeyEncoded = uri.getQueryParameter("pub_key");

          if (TextUtils.isEmpty(ephemeralId) || TextUtils.isEmpty(publicKeyEncoded)) {
            Log.w(TAG, "UUID or Key is empty!");
            return BAD_CODE;
          }

          ECPublicKey      publicKey         = Curve.decodePoint(Base64.decode(publicKeyEncoded), 0);
          IdentityKeyPair  identityKeyPair   = IdentityKeyUtil.getIdentityKeyPair(context);
          Optional<byte[]> profileKey        = Optional.of(ProfileKeyUtil.getProfileKey(getContext()));

          TextSecurePreferences.setMultiDevice(DeviceActivity.this, true);
          TextSecurePreferences.setIsUnidentifiedDeliveryEnabled(context, false);
          accountManager.addDevice(ephemeralId, publicKey, identityKeyPair, profileKey, verificationCode);

          return SUCCESS;
        } catch (NotFoundException e) {
          Log.w(TAG, e);
          TextSecurePreferences.setMultiDevice(DeviceActivity.this, isMultiDevice);
          return NO_DEVICE;
        } catch (DeviceLimitExceededException e) {
          Log.w(TAG, e);
          TextSecurePreferences.setMultiDevice(DeviceActivity.this, isMultiDevice);
          return LIMIT_EXCEEDED;
        } catch (IOException e) {
          Log.w(TAG, e);
          TextSecurePreferences.setMultiDevice(DeviceActivity.this, isMultiDevice);
          return NETWORK_ERROR;
        } catch (InvalidKeyException e) {
          Log.w(TAG, e);
          TextSecurePreferences.setMultiDevice(DeviceActivity.this, isMultiDevice);
          return KEY_ERROR;
        }
      }

      @Override
      protected void onPostExecute(Integer result) {
        super.onPostExecute(result);

        Context context = DeviceActivity.this;

        switch (result) {
          case SUCCESS:
            Toast.makeText(context, R.string.DeviceProvisioningActivity_content_progress_success, Toast.LENGTH_SHORT).show();
            finish();
            return;
          case NO_DEVICE:
            Toast.makeText(context, R.string.DeviceProvisioningActivity_content_progress_no_device, Toast.LENGTH_LONG).show();
            break;
          case NETWORK_ERROR:
            Toast.makeText(context, R.string.DeviceProvisioningActivity_content_progress_network_error, Toast.LENGTH_LONG).show();
            break;
          case KEY_ERROR:
            Toast.makeText(context, R.string.DeviceProvisioningActivity_content_progress_key_error, Toast.LENGTH_LONG).show();
            break;
          case LIMIT_EXCEEDED:
            Toast.makeText(context, R.string.DeviceProvisioningActivity_sorry_you_have_too_many_devices_linked_already, Toast.LENGTH_LONG).show();
            break;
          case BAD_CODE:
            Toast.makeText(context, R.string.DeviceActivity_sorry_this_is_not_a_valid_device_link_qr_code, Toast.LENGTH_LONG).show();
            break;
        }

        getSupportFragmentManager().popBackStackImmediate();
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }
}
