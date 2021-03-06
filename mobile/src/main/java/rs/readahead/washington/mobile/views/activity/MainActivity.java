package rs.readahead.washington.mobile.views.activity;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.MenuItem;
import android.view.OrientationEventListener;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.javarosa.core.model.FormDef;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import permissions.dispatcher.NeedsPermission;
import permissions.dispatcher.OnNeverAskAgain;
import permissions.dispatcher.OnPermissionDenied;
import permissions.dispatcher.OnShowRationale;
import permissions.dispatcher.PermissionRequest;
import permissions.dispatcher.RuntimePermissions;
import rs.readahead.washington.mobile.MyApplication;
import rs.readahead.washington.mobile.R;
import rs.readahead.washington.mobile.bus.EventCompositeDisposable;
import rs.readahead.washington.mobile.bus.EventObserver;
import rs.readahead.washington.mobile.bus.event.LocaleChangedEvent;
import rs.readahead.washington.mobile.data.sharedpref.Preferences;
import rs.readahead.washington.mobile.domain.entity.Metadata;
import rs.readahead.washington.mobile.domain.entity.collect.CollectForm;
import rs.readahead.washington.mobile.mvp.contract.ICollectCreateFormControllerContract;
import rs.readahead.washington.mobile.mvp.contract.IHomeScreenPresenterContract;
import rs.readahead.washington.mobile.mvp.contract.IMetadataAttachPresenterContract;
import rs.readahead.washington.mobile.mvp.presenter.CollectCreateFormControllerPresenter;
import rs.readahead.washington.mobile.mvp.presenter.HomeScreenPresenter;
import rs.readahead.washington.mobile.odk.FormController;
import rs.readahead.washington.mobile.util.C;
import rs.readahead.washington.mobile.util.DialogsUtil;
import rs.readahead.washington.mobile.util.PermissionUtil;
import rs.readahead.washington.mobile.views.custom.CountdownImageView;
import rs.readahead.washington.mobile.views.custom.HomeScreenGradient;


@RuntimePermissions
public class MainActivity extends MetadataActivity implements
        IMetadataAttachPresenterContract.IView,
        IHomeScreenPresenterContract.IView,
        ICollectCreateFormControllerContract.IView {
    @BindView(R.id.main_toolbar)
    Toolbar toolbar;
    @BindView(R.id.panic_mode_view)
    RelativeLayout panicCountdownView;
    @BindView(R.id.panic_explain_view)
    LinearLayout panicExplainView;
    @BindView(R.id.countdown_timer)
    CountdownImageView countdownImage;
    @BindView(R.id.camera_tools_container)
    View cameraToolsContainer;
    @BindView(R.id.main_container)
    View mainView;
    @BindView(R.id.home_screen_gradient)
    HomeScreenGradient homeScreenGradient;
    @BindView(R.id.panic_seek)
    SeekBar panicSeekBar;
    @BindView(R.id.panic_bar)
    LinearLayout panicSeekBarView;
    @BindView(R.id.tab_button_collect)
    View buttonCollect;
    @BindView(R.id.tab_button_gallery)
    View buttonGallery;
    @BindView(R.id.nav_bar)
    View navigationBar;
    @BindView(R.id.link)
    TextView setPanicTextView;
    @BindView(R.id.background)
    View background;


    private boolean mExit = false;
    private Handler handler;
    private boolean panicActivated;
    private EventCompositeDisposable disposables;
    private AlertDialog alertDialog;
    private HomeScreenPresenter homeScreenPresenter;
    private CollectCreateFormControllerPresenter formControllerPresenter;
    private ProgressDialog progressDialog;
    private OrientationEventListener mOrientationEventListener;
    private boolean isPhoneListEmpty;
    private int timerDuration;
    private MenuItem settingsMenuItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);
        ButterKnife.bind(this);

        setupToolbar();

        handler = new Handler();

        homeScreenPresenter = new HomeScreenPresenter(this, getCacheWordHandler());

        panicActivated = false;
        timerDuration = getResources().getInteger(R.integer.panic_countdown_duration);
        initSetup();

        // todo: check this..
        //SafetyNetCheck.setApiKey(getString(R.string.share_in_report));
    }

    private void initSetup() {
        //handleOrbot();
        setupPanicSeek();

        setOrientationListener();

        setPanicTextView.setText(Html.fromHtml(getString(R.string.set_panic_mode_link)));
        setPanicTextView.setMovementMethod(LinkMovementMethod.getInstance());
        setPanicTextView.setLinkTextColor(getResources().getColor(R.color.wa_light_gray));

        disposables = MyApplication.bus().createCompositeDisposable();
        disposables.wire(LocaleChangedEvent.class, new EventObserver<LocaleChangedEvent>() {
            @Override
            public void onNext(LocaleChangedEvent event) {
                recreate();
            }
        });
    }

    private void setupToolbar() {
        toolbar.inflateMenu(R.menu.activity_main_menu);

        settingsMenuItem = toolbar.getMenu().findItem(R.id.nav_settings);
        settingsMenuItem.setOnMenuItemClickListener(item -> {
            startSettingsActivity();
            return true;
        });

        toolbar.getMenu().findItem(R.id.nav_exit).setOnMenuItemClickListener(item -> {
            exitApp();
            return true;
        });

        MenuItem offlineMenuItem = toolbar.getMenu().findItem(R.id.nav_offline_mode);
        offlineMenuItem.setOnMenuItemClickListener(item -> {
            DialogsUtil.showOfflineSwitchDialog(MainActivity.this, t -> setOfflineMenuIcon(item, t));
            return true;
        });
    }

    private void setOfflineMenuIcon(MenuItem offlineMenuIcon, boolean offline) {
        offlineMenuIcon.setIcon(offline ? R.drawable.ic_cloud_off_white_24dp : R.drawable.ic_cloud_queue_white_24dp);
        // todo (djm): move this from here - this should be setup once per activity.resume()
        offlineMenuIcon.setVisible(Preferences.isCollectServersLayout());
        if (settingsMenuItem != null) {
            settingsMenuItem.setShowAsAction(Preferences.isCollectServersLayout() ? MenuItem.SHOW_AS_ACTION_NEVER : MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
    }

    @OnClick({R.id.tab_button_record, R.id.tab_button_collect, R.id.tab_button_gallery})
    void onBottomNavigationTabClick(View view) {
        switch (view.getId()) {
            case R.id.tab_button_record:
                onMicrophoneClicked();
                break;

            case R.id.tab_button_collect:
                startCollectActivity();
                break;

            case R.id.tab_button_gallery:
                showGallery(false);
                break;

        }
    }

    @OnClick(R.id.panic_mode_view)
    void onPanicClicked() {
        hidePanicScreens();
        stopPanicking();
    }

    @OnClick(R.id.panic_explain_view)
    void onExplainClicked() {
        hidePanicScreens();
        stopPanicking();
    }

    private void stopPanicking() {
        countdownImage.cancel();
        countdownImage.setCountdownNumber(timerDuration);
        panicActivated = false;
        resetSeekBar(panicSeekBar);
        showMainControls();
    }

    @OnClick(R.id.camera_tools_container)
    void onContainerClicked() {
        if (PermissionUtil.checkPermission(this, Manifest.permission.CAMERA)) {
            if (Preferences.isAnonymousMode()) {
                MainActivityPermissionsDispatcher.switchToCameraModeAnonymousWithPermissionCheck(this);
            } else {
                MainActivityPermissionsDispatcher.switchToCameraModeLocationWithPermissionCheck(this);
            }
        }
    }

    //@OnClick(R.id.microphone)
    void onMicrophoneClicked() {
        if (Preferences.isAnonymousMode()) {
            startAudioRecorderActivityAnonymous();
        } else {
            MainActivityPermissionsDispatcher.startAudioRecorderActivityLocationWithPermissionCheck(this);
        }
    }

    @NeedsPermission(Manifest.permission.CAMERA)
    void enableCamera() {
        recreate(); // we have permissions, recreate activity to show preview..
    }

    @NeedsPermission({Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO})
    public void switchToCameraModeLocation() {
        checkLocationSettings(C.START_CAMERA_CAPTURE, this::switchToCameraModeWithLocationChecked);
    }

    @NeedsPermission({Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO})
    public void switchToCameraModeAnonymous() {
        switchToCameraMode();
    }

    private void switchToCameraModeWithLocationChecked() {
        startLocationMetadataListening();
        switchToCameraMode();
    }

    @NeedsPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    public void startAudioRecorderActivityLocation() {
        checkLocationSettings(C.START_AUDIO_RECORD, this::startAudioRecordActivityWithLocationChecked);
    }

    private void startAudioRecorderActivityAnonymous() {
        startAudioRecordActivityWithLocationChecked();
    }

    private void startAudioRecordActivityWithLocationChecked() {
        Intent intent = new Intent(this, AudioRecordActivity2.class);
        intent.putExtra(AudioRecordActivity2.RECORDER_MODE, AudioRecordActivity2.Mode.STAND.name());
        startActivityForResult(intent, C.RECORDED_AUDIO);
    }

    @OnShowRationale(Manifest.permission.ACCESS_FINE_LOCATION)
    void showFineLocationRationale(final PermissionRequest request) {
        alertDialog = PermissionUtil.showRationale(
                this, request, getString(R.string.ra_media_location_permissions));
    }

    @OnShowRationale(Manifest.permission.CAMERA)
    void showCameraRationale(final PermissionRequest request) {
        alertDialog = PermissionUtil.showRationale(this, request, getString(R.string.ra_camera_preview));
    }

    @OnShowRationale({Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO})
    void showCameraAndAudioRationale(final PermissionRequest request) {
        alertDialog = PermissionUtil.showRationale(this, request, getString(R.string.ra_camera_rationale));
    }

    @OnShowRationale({Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO})
    void showLocationCameraAndAudioRationale(final PermissionRequest request) {
        alertDialog = PermissionUtil.showRationale(this, request, getString(R.string.ra_camera_rationale));
    }

    @OnPermissionDenied(Manifest.permission.CAMERA)
    void onCameraPermissionDenied() {
    }

    @OnPermissionDenied(Manifest.permission.ACCESS_FINE_LOCATION)
    void onFineLocationPermissionDenied() {
        startAudioRecordActivityWithLocationChecked();
    }

    @OnPermissionDenied({Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO})
    void onCameraAndAudioPermissionDenied() {
    }

    @OnPermissionDenied({Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO})
    void onLocationCameraAndAudioPermissionDenied() {
    }

    @OnNeverAskAgain(Manifest.permission.ACCESS_FINE_LOCATION)
    void onFineLocationNeverAskAgain() {
        startAudioRecordActivityWithLocationChecked();
    }

    @OnNeverAskAgain(Manifest.permission.CAMERA)
    void onCameraNeverAskAgain() {
    }

    @OnNeverAskAgain({Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO})
    void onCameraAndAudioNeverAskAgain() {
    }

    @OnNeverAskAgain({Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO})
    void onLocationCameraAndAudioNeverAskAgain() {
    }

    private void startCollectActivity() {
        startActivity(new Intent(MainActivity.this, CollectMainActivity.class));
    }

    private void startSettingsActivity() {
        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
    }

    private void showGallery(boolean animated) {
        if (Preferences.isAnonymousMode()) {
            startGalleryActivity(animated);
        } else {
            MainActivityPermissionsDispatcher.startGalleryActivityWithPermissionCheck(this, animated);
        }
    }

    @NeedsPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    public void startGalleryActivity(boolean animated) {
        startActivity(new Intent(MainActivity.this, GalleryActivity.class)
                .putExtra(GalleryActivity.GALLERY_ANIMATED, animated));
    }

    private boolean isLocationSettingsRequestCode(int requestCode) {
        return requestCode == C.START_CAMERA_CAPTURE ||
                requestCode == C.START_AUDIO_RECORD;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!isLocationSettingsRequestCode(requestCode) && resultCode != RESULT_OK) {
            return; // user canceled evidence acquiring
        }

        switch (requestCode) {
            case C.START_CAMERA_CAPTURE:
                switchToCameraModeWithLocationChecked();
                break;

            case C.START_AUDIO_RECORD:
                startAudioRecordActivityWithLocationChecked();
                break;

            case C.CAMERA_CAPTURE:
            case C.RECORDED_AUDIO:
                // everything is done already..
                break;
        }
    }

    /*private BroadcastReceiver torStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //if (TextUtils.equals(intent.getAction(), OrbotHelper.ACTION_STATUS)) {
            //    String status = intent.getStringExtra(OrbotHelper.EXTRA_STATUS);
            //    // TODO: See what is going on with TOR
            //    boolean enabled = status.equals(OrbotHelper.STATUS_ON);
            //}
        }
    };*/

    /*private void handleOrbot() {
        // todo: for now we don't want to ask for TOR at application first start
        SharedPrefs.getInstance().setAskForTorOnStart(false);

        if (SharedPrefs.getInstance().isTorModeActive()) {
            //checkNetworkSecurity();
        } else if (SharedPrefs.getInstance().askForTorOnStart()) {
            DialogsUtil.showMessageOKCancelWithTitle(this, getString(R.string.orbot_activation),
                    getString(R.string.attention), getString(R.string.ok), getString(R.string.cancel),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            //checkNetworkSecurity();
                            dialog.dismiss();
                        }
                    },
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            SharedPrefs.getInstance().setAskForTorOnStart(false);
                            dialog.dismiss();
                        }
                    });
        }
    }*/

    /*private void checkNetworkSecurity() {
        if (OrbotHelper.isOrbotInstalled(this)) {
            OrbotHelper.requestStartTor(this);
            SharedPrefs.getInstance().setToreModeActive(true);
        } else
            DialogsUtil.showOrbotDialog(this);
    }*/

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        MainActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    @Override
    public void onBackPressed() {
        if (maybeClosePanic()) return;
        // if (maybeCloseCamera()) return;
        if (!checkIfShouldExit()) return;
        closeApp();
    }

    private void closeApp() {
        finish();
        lockCacheWord();
    }

    private void exitApp() {
        MyApplication.exit(this);
        lockCacheWord();
    }

    private boolean checkIfShouldExit() {
        if (!mExit) {
            mExit = true;
            showToast(R.string.exit);
            handler.postDelayed(() -> mExit = false, 3 * 1000);
            return false;
        }
        return true;
    }

    private boolean maybeClosePanic() {
        if (panicCountdownView.getVisibility() == View.VISIBLE || panicExplainView.getVisibility() == View.VISIBLE) {
            stopPanicking();
            hidePanicScreens();
        }

        return false; // todo: check panic state here
    }

    private void lockCacheWord() {
        if (!getCacheWordHandler().isLocked()) {
            getCacheWordHandler().lock();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (disposables != null) {
            disposables.dispose();
        }

        stopPresenter();
        hideProgressDialog();
    }

    @Override
    protected void onResume() {
        super.onResume();

        //homeScreenPresenter.loadPhoneList();
        isPhoneListEmpty = false;

        setupPanicView();
        setupButtonsTab();

        startLocationMetadataListening();

        mOrientationEventListener.enable();

        if (panicActivated) {
            showPanicScreens();
            panicActivated = false;
        } else {
            maybeClosePanic();
        }

        setOfflineMenuIcon(toolbar.getMenu().findItem(R.id.nav_offline_mode), Preferences.isOfflineMode());
    }

    @Override
    protected void onPause() {
        super.onPause();

        stopLocationMetadataListening();

        mOrientationEventListener.disable();

        if (countdownImage.isCounting()) {
            panicActivated = true;
        }
    }

    @Override
    protected void onStop() {
        if (alertDialog != null && alertDialog.isShowing()) {
            alertDialog.dismiss();
        }

        super.onStop();
    }

    //@NeedsPermission(Manifest.permission.SEND_SMS)
    public void showPanicScreens() {
        // hide controls
        //cameraToolsContainer.setVisibility(View.GONE);
        hideMainControls();

        // really show panic screen
        if (isPhoneListEmpty) {
            panicExplainView.setVisibility(View.VISIBLE);
            panicExplainView.setAlpha(1);
        } else {
            panicCountdownView.setVisibility(View.VISIBLE);
            panicCountdownView.setAlpha(1);
            countdownImage.start(timerDuration, this::executePanicMode);
        }
    }

    private void hideMainControls() {
        cameraToolsContainer.setVisibility(View.GONE);
        navigationBar.setVisibility(View.GONE);
        toolbar.setVisibility(View.GONE);
    }

    private void showMainControls() {
        cameraToolsContainer.setVisibility(View.VISIBLE);
        navigationBar.setVisibility(View.VISIBLE);
        toolbar.setVisibility(View.VISIBLE);
    }
    /*//@OnShowRationale(Manifest.permission.SEND_SMS)
    void onShowPanicScreensRationale(final PermissionRequest request) {
        alertDialog = PermissionUtil.showRationale(this, request, getString(R.string.permission_sms));
    }

    //@OnPermissionDenied(Manifest.permission.SEND_SMS)
    void onShowPanicScreensPermissionDenied() {
        hidePanicScreens();
    }

    //@OnNeverAskAgain(Manifest.permission.SEND_SMS)
    void onShowPanicScreensNeverAskAgain() {
    }*/

    void executePanicMode() {
        try {
            if (homeScreenPresenter != null) {
                homeScreenPresenter.executePanicMode();
            }
        } catch (Throwable ignored) {
            panicActivated = true;
        }
    }

    @Override
    public void onMetadataAttached(long mediaFileId, @Nullable Metadata metadata) {
        Intent data = new Intent();
        data.putExtra(C.CAPTURED_MEDIA_FILE_ID, mediaFileId);

        setResult(RESULT_OK, data);
    }

    @Override
    public void onMetadataAttachError(Throwable throwable) {
        // onAddError(throwable);
    }

    @NeedsPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    public void startCollectFormEntryActivity() {
        startActivity(new Intent(this, CollectFormEntryActivity.class));
    }

    @Override
    public void onFormControllerCreated(FormController formController) {
        if (Preferences.isAnonymousMode()) {
            startCollectFormEntryActivity(); // no need to check for permissions, as location won't be turned on
        } else {
            MainActivityPermissionsDispatcher.startCollectFormEntryActivityWithPermissionCheck(this);
        }
    }

    @Override
    public void onFormControllerError(Throwable error) {

    }

    @Override
    public Context getContext() {
        return this;
    }

    @Override
    public void onPhoneListLoaded(boolean isListEmpty) {
        isPhoneListEmpty = isListEmpty;
    }

    @Override
    public void onPhoneListLoadError(Throwable throwable) {
    }

    @Override
    public void getCollectFormSuccess(CollectForm form, FormDef formDef) {
        startCreateFormControllerPresenter(form, formDef);
    }

    @Override
    public void onCollectFormError(Throwable throwable) {
        showToast(R.string.shortcut_collect_form_not_loaded);
    }

    private void stopPresenter() {
        if (homeScreenPresenter != null) {
            homeScreenPresenter.destroy();
            homeScreenPresenter = null;
        }
    }

    private void setupPanicView() {
        if (Preferences.isQuickExit()) {
            panicSeekBarView.setVisibility(View.VISIBLE);
        } else {
            panicSeekBarView.setVisibility(View.GONE);
        }
    }

    private void setupPanicSeek() {
        panicSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                if (b) {
                    blendPanicScreens(i);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (seekBar.getProgress() > 0) {
                    hidePanicScreens();
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (seekBar.getProgress() == 100) {
                    panicSeekBarView.setVisibility(View.GONE);
                    seekBar.setProgress(0);
                    //MainActivityPermissionsDispatcher.showPanicScreensWithCheck(MainActivity.this);
                    showPanicScreens();
                } else {
                    hidePanicScreens();
                }
            }
        });
    }

    private void hidePanicScreens() {
        resetSeekBar(panicSeekBar);

        toolbar.setAlpha(1);
        toolbar.setVisibility(View.VISIBLE);
        cameraToolsContainer.setAlpha(1);
        cameraToolsContainer.setVisibility(View.VISIBLE);
        background.setAlpha(0f);

        setupPanicView();

        if (isPhoneListEmpty) {
            panicExplainView.setVisibility(View.GONE);
        } else {
            panicCountdownView.setVisibility(View.GONE);
        }
    }

    private void blendPanicScreens(int i) {
        toolbar.setAlpha((float) (100 - i) / 100);
        cameraToolsContainer.setAlpha((float) (100 - i) / 100);
        background.setAlpha((float) i / 100);
        if (isPhoneListEmpty) {
            panicExplainView.setVisibility(i == 100 ? View.VISIBLE : View.GONE);
        } else {
            panicCountdownView.setVisibility(i == 100 ? View.VISIBLE : View.GONE);
        }
        panicSeekBarView.setVisibility(i == 100 ? View.INVISIBLE : View.VISIBLE);
    }

    private void hideProgressDialog() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private void switchToCameraMode() {
        Intent intent = new Intent(this, CameraActivity.class);
        intent.putExtra(CameraActivity.INTENT_MODE, CameraActivity.IntentMode.STAND.name());
        startActivity(intent);
    }

    private void setOrientationListener() {
        mOrientationEventListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                //if (!isInCameraMode) return;
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;
                // handle rotation for tablets;
            }
        };
    }

    private void startCreateFormControllerPresenter(CollectForm form, FormDef formDef) {
        stopCreateFormControllerPresenter();
        formControllerPresenter = new CollectCreateFormControllerPresenter(this);
        formControllerPresenter.createFormController(form, formDef);
    }

    private void stopCreateFormControllerPresenter() {
        if (formControllerPresenter != null) {
            formControllerPresenter.destroy();
            formControllerPresenter = null;
        }
    }

    private void resetSeekBar(SeekBar seekbar) {
        if (Build.VERSION.SDK_INT >= 24) {
            seekbar.setProgress(0, true);
        } else {
            seekbar.setProgress(0);
        }
    }

    private void setupButtonsTab() {
        if (Preferences.isCollectServersLayout()) {
            buttonCollect.setVisibility(View.VISIBLE);
            buttonCollect.setBackground(getContext().getResources().getDrawable(R.drawable.round_left_button_background));
            buttonGallery.setBackground(getContext().getResources().getDrawable(R.drawable.central_button_background));
        } else {
            buttonCollect.setVisibility(View.GONE);
            buttonGallery.setBackground(getContext().getResources().getDrawable(R.drawable.round_left_button_background));
        }
    }
}