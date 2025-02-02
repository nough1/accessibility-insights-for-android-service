// Portions Copyright (c) Microsoft Corporation
// Licensed under the MIT License.
//
// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.microsoft.accessibilityinsightsforandroidservice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import com.google.gson.GsonBuilder;

public class AccessibilityInsightsForAndroidService extends AccessibilityService {
  private static final String TAG = "AccessibilityInsightsForAndroidService";
  private final AxeScanner axeScanner;
  private final ATFAScanner atfaScanner;
  private final EventHelper eventHelper;
  private final DeviceConfigFactory deviceConfigFactory;
  private final OnScreenshotAvailableProvider onScreenshotAvailableProvider =
      new OnScreenshotAvailableProvider();
  private final BitmapProvider bitmapProvider = new BitmapProvider();
  private HandlerThread screenshotHandlerThread = null;
  private ScreenshotController screenshotController = null;
  private int activeWindowId = -1; // Set initial state to an invalid ID
  private FocusVisualizationStateManager focusVisualizationStateManager;
  private FocusVisualizer focusVisualizer;
  private FocusVisualizerController focusVisualizerController;
  private FocusVisualizationCanvas focusVisualizationCanvas;
  private AccessibilityEventDispatcher accessibilityEventDispatcher;
  private DeviceOrientationHandler deviceOrientationHandler;
  private TempFileProvider tempFileProvider;

  public AccessibilityInsightsForAndroidService() {
    deviceConfigFactory = new DeviceConfigFactory();
    axeScanner =
        AxeScannerFactory.createAxeScanner(deviceConfigFactory, this::getRealDisplayMetrics);
    atfaScanner = ATFAScannerFactory.createATFAScanner(this);
    eventHelper = new EventHelper(new ThreadSafeSwapper<>());
  }

  private DisplayMetrics getRealDisplayMetrics() {
    // Correct screen metrics are only accessible within the context of the running
    // service. They're not available when the service initializes, hence the callback
    return DisplayMetricsHelper.getRealDisplayMetrics(this);
  }

  private void stopScreenshotHandlerThread() {
    if (screenshotHandlerThread != null) {
      screenshotHandlerThread.quit();
      screenshotHandlerThread = null;
    }

    screenshotController = null;
  }

  @Override
  protected void onServiceConnected() {
    Logger.logVerbose(TAG, "*** onServiceConnected");



    AccessibilityServiceInfo info = new AccessibilityServiceInfo();
    info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
    info.feedbackType = AccessibilityEvent.TYPES_ALL_MASK;
    info.notificationTimeout = 0;
    info.flags =
        AccessibilityServiceInfo.DEFAULT
            | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;

    setServiceInfo(info);

    stopScreenshotHandlerThread();
    screenshotHandlerThread = new HandlerThread("ScreenshotHandlerThread");
    screenshotHandlerThread.start();
    Handler screenshotHandler = new Handler(screenshotHandlerThread.getLooper());

    screenshotController =
        new ScreenshotController(
            this::getRealDisplayMetrics,
            screenshotHandler,
            onScreenshotAvailableProvider,
            bitmapProvider,
            MediaProjectionHolder::get);

    SynchronizedRequestDispatcher.SharedInstance.teardown();
    tempFileProvider = new TempFileProvider(getApplicationContext());
    tempFileProvider.cleanOldFilesBestEffort();

    WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    focusVisualizationStateManager = new FocusVisualizationStateManager();
    LayoutParamGenerator layoutParamGenerator = new LayoutParamGenerator(this::getRealDisplayMetrics);
    focusVisualizationCanvas = new FocusVisualizationCanvas(this);
    focusVisualizer = new FocusVisualizer(new FocusVisualizerStyles(), focusVisualizationCanvas);
    focusVisualizerController = new FocusVisualizerController(focusVisualizer, focusVisualizationStateManager, new UIThreadRunner(), windowManager, layoutParamGenerator, focusVisualizationCanvas, new DateProvider());
    accessibilityEventDispatcher = new AccessibilityEventDispatcher();
    deviceOrientationHandler = new DeviceOrientationHandler(getResources().getConfiguration().orientation);
    RootNodeFinder rootNodeFinder = new RootNodeFinder();
    ResultsV2ContainerSerializer resultsV2ContainerSerializer = new ResultsV2ContainerSerializer(
        new ATFARulesSerializer(),
        new ATFAResultsSerializer(new GsonBuilder()),
        new GsonBuilder());

    setupFocusVisualizationListeners();

    RequestDispatcher requestDispatcher = new RequestDispatcher(rootNodeFinder, screenshotController, eventHelper, axeScanner, atfaScanner, deviceConfigFactory, focusVisualizationStateManager, resultsV2ContainerSerializer);
    SynchronizedRequestDispatcher.SharedInstance.setup(requestDispatcher);
  }

  private void setupFocusVisualizationListeners() {
    accessibilityEventDispatcher.addOnRedrawEventListener(focusVisualizerController::onRedrawEvent);
    accessibilityEventDispatcher.addOnFocusEventListener(focusVisualizerController::onFocusEvent);
    accessibilityEventDispatcher.addOnAppChangedListener(focusVisualizerController::onAppChanged);
    deviceOrientationHandler.subscribe(focusVisualizerController::onOrientationChanged);
  }

  @Override
  public boolean onUnbind(Intent intent) {
    Logger.logVerbose(TAG, "*** onUnbind");
    SynchronizedRequestDispatcher.SharedInstance.teardown();
    tempFileProvider.cleanOldFilesBestEffort();
    stopScreenshotHandlerThread();
    MediaProjectionHolder.cleanUp();
    return false;
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {

    // debug for use
    //android.os.Debug.waitForDebugger();
    accessibilityEventDispatcher.onAccessibilityEvent(event, getRootInActiveWindow());

    // This logic ensures that we only track events from the active window, as
    // described under "Retrieving window content" of the Android service docs at
    // https://www.android-doc.com/reference/android/accessibilityservice/AccessibilityService.html
    int windowId = event.getWindowId();

    int eventType = event.getEventType();
    if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        || eventType == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
        || eventType == AccessibilityEvent.TYPE_VIEW_HOVER_EXIT) {
      activeWindowId = windowId;
    }

    if (activeWindowId == windowId) {
      eventHelper.recordEvent(getRootInActiveWindow());
    }
    if(event.getPackageName()!=null && event.getPackageName().toString().contains("twitter")) {

      Logger.logVerbose(TAG, "receive twitter event:" + event);

      this.startScreenshotActivity();

    }else{

      if(event.getPackageName()==null ||
              (event.getPackageName().toString().contains("com.huawei.intelligent")||
                      (event.getPackageName().toString().contains("com.android.systemui"))
              ||(event.getPackageName().toString().contains("com.huawei.android.launcher")
                      ))){
        Logger.logVerbose(TAG, "not intercept event :" + event);
      }else {
        if(event.getPackageName().toString().contains("accessibilityinsightsforandroidservice")) {
          this.startScreenshotActivity();
          Logger.logVerbose(TAG, "receive event:" + event);
        }else{

          if(interceptCleanSettings(event)){
            this.startScreenshotActivity();
            Logger.logVerbose(TAG, "receive event:" + event);
          }else {
            Logger.logVerbose(TAG, "not intercept event:" + event);
          }
        }

      }

    }
    }

  /**
   * 当用户想要关闭 accessibility 的时候，需要拦截 
   * @param event
   * @return
   */
    public boolean interceptCleanSettings(AccessibilityEvent event){

      if(event.getPackageName()==null){
        return false;
      }
      if(event.getClassName()==null){
        return false;
      }

      if(event.getClassName().toString().contains("com.android.settings.CleanSubSettings")){

        Logger.logVerbose(TAG,"needIntercept:"+event);
        if(event.getText()==null){
          return false;
        }
        for(CharSequence charSequence:event.getText()){

          if(charSequence.toString().contains("注意力守护神0.1")){
            return true ;
          }
        }
      }

      return false;
    }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    this.deviceOrientationHandler.setOrientation(newConfig.orientation);
  }

  @Override
  public void onInterrupt() {}

  private void startScreenshotActivity() {
    Intent startScreenshot = new Intent(this, ScreenshotActivity.class);
    startScreenshot.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(startScreenshot);
  }
}
