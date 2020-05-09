package com.shizy.readingservice;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;

import com.baidu.tts.chainofresponsibility.logger.LoggerProxy;
import com.baidu.tts.client.SpeechSynthesizer;
import com.baidu.tts.client.SpeechSynthesizerListener;
import com.baidu.tts.client.SynthesizerTool;
import com.baidu.tts.client.TtsMode;
import com.shizy.readingservice.control.InitConfig;
import com.shizy.readingservice.control.MySyntherizer;
import com.shizy.readingservice.control.NonBlockSyntherizer;
import com.shizy.readingservice.listener.UiMessageListener;
import com.shizy.readingservice.util.Auth;
import com.shizy.readingservice.util.AutoCheck;
import com.shizy.readingservice.util.IOfflineResourceConst;
import com.shizy.readingservice.util.OfflineResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ReadingService extends AccessibilityService {

    private static final String TAG = ReadingService.class.getSimpleName();

    private TtsMode ttsMode = IOfflineResourceConst.DEFAULT_SDK_TTS_MODE;
    protected boolean isOnlineSDK = TtsMode.ONLINE.equals(IOfflineResourceConst.DEFAULT_SDK_TTS_MODE);

    // 离线发音选择，VOICE_FEMALE即为离线女声发音。
    // assets目录下bd_etts_common_speech_m15_mand_eng_high_am-mix_vXXXXXXX.dat为离线男声模型文件；
    // assets目录下bd_etts_common_speech_f7_mand_eng_high_am-mix_vXXXXX.dat为离线女声模型文件;
    // assets目录下bd_etts_common_speech_yyjw_mand_eng_high_am-mix_vXXXXX.dat 为度逍遥模型文件;
    // assets目录下bd_etts_common_speech_as_mand_eng_high_am_vXXXX.dat 为度丫丫模型文件;
    // 在线合成sdk下面的参数不生效
    protected String offlineVoice = OfflineResource.VOICE_MALE;

    private String appId;
    private String appKey;
    private String secretKey;
    private String sn; // 纯离线合成SDK授权码；离在线合成SDK免费，没有此参数

    // 主控制类，所有合成控制方法从这个类开始
    protected MySyntherizer synthesizer;

    private Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            if (msg.what == 100) {
                AutoCheck autoCheck = (AutoCheck) msg.obj;
                synchronized (autoCheck) {
                    String message = autoCheck.obtainDebugMessage();
                    toPrint(message);
                }
            }
        }
    };

    private static final String CLASS_NAME_PREVIEW_ACTIVITY = "com.tencent.mm.ui.chatting.TextPreviewUI";
    private static final String CLASS_NAME_TEXT_VIEW = "android.widget.TextView";

    @Override
    public void onCreate() {
        super.onCreate();
        appId = Auth.getInstance(this).getAppId();
        appKey = Auth.getInstance(this).getAppKey();
        secretKey = Auth.getInstance(this).getSecretKey();
        sn = Auth.getInstance(this).getSn(); // 离线合成SDK必须有此参数；在线合成SDK免费，没有此参数
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (synthesizer != null) {
            synthesizer.release();
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.e("shizy", "onServiceConnected: ");

        initialTts(); // 初始化TTS引擎
        if (!isOnlineSDK) {
            toPrint(SynthesizerTool.getEngineInfo());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
//        Log.e("shizy", "event: " + event);

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (TextUtils.equals(event.getClassName(), CLASS_NAME_PREVIEW_ACTIVITY)) {// 消息预览
                AccessibilityNodeInfo node = findTextViewNodeInfo(getRootInActiveWindow());
                if (node != null) {
                    Log.e("shizy", "text: " + node.getText());
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        if (synthesizer != null) {
            synthesizer.stop();
            synthesizer.release();
        }
    }

    private AccessibilityNodeInfo findTextViewNodeInfo(AccessibilityNodeInfo parent) {
        int cnt = parent.getChildCount();
        for (int i = 0; i < cnt; i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (TextUtils.equals(child.getClassName(), CLASS_NAME_TEXT_VIEW)) {
                return child;
            }
            return findTextViewNodeInfo(child);
        }
        return null;
    }

    protected void initialTts() {
        LoggerProxy.printable(true); // 日志打印在logcat中
        // 设置初始化参数
        // 此处可以改为 含有您业务逻辑的SpeechSynthesizerListener的实现类
        SpeechSynthesizerListener listener = new UiMessageListener(mainHandler);
        InitConfig config = getInitConfig(listener);
        synthesizer = new MySyntherizer(this, config, mainHandler); // 此处可以改为MySyntherizer 了解调用过程
    }

    protected Map<String, String> getParams() {
        Map<String, String> params = new HashMap<>();
        // 以下参数均为选填
        // 设置在线发声音人： 0 普通女声（默认） 1 普通男声 3 情感男声<度逍遥> 4 情感儿童声<度丫丫>, 其它发音人见文档
        params.put(SpeechSynthesizer.PARAM_SPEAKER, "0");
        // 设置合成的音量，0-15 ，默认 5
        params.put(SpeechSynthesizer.PARAM_VOLUME, "15");
        // 设置合成的语速，0-15 ，默认 5
        params.put(SpeechSynthesizer.PARAM_SPEED, "5");
        // 设置合成的语调，0-15 ，默认 5
        params.put(SpeechSynthesizer.PARAM_PITCH, "5");
        if (!isOnlineSDK) {
            // 免费的在线SDK版本没有此参数。

            /*
            params.put(SpeechSynthesizer.PARAM_MIX_MODE, SpeechSynthesizer.MIX_MODE_DEFAULT);
            // 该参数设置为TtsMode.MIX生效。即纯在线模式不生效。
            // MIX_MODE_DEFAULT 默认 ，wifi状态下使用在线，非wifi离线。在线状态下，请求超时6s自动转离线
            // MIX_MODE_HIGH_SPEED_SYNTHESIZE_WIFI wifi状态下使用在线，非wifi离线。在线状态下， 请求超时1.2s自动转离线
            // MIX_MODE_HIGH_SPEED_NETWORK ， 3G 4G wifi状态下使用在线，其它状态离线。在线状态下，请求超时1.2s自动转离线
            // MIX_MODE_HIGH_SPEED_SYNTHESIZE, 2G 3G 4G wifi状态下使用在线，其它状态离线。在线状态下，请求超时1.2s自动转离线
            // params.put(SpeechSynthesizer.PARAM_MIX_MODE_TIMEOUT, SpeechSynthesizer.PARAM_MIX_TIMEOUT_TWO_SECOND);
            // 离在线模式，强制在线优先。在线请求后超时2秒后，转为离线合成。
            */
            // 离线资源文件， 从assets目录中复制到临时目录，需要在initTTs方法前完成
            OfflineResource offlineResource = createOfflineResource(offlineVoice);
            // 声学模型文件路径 (离线引擎使用), 请确认下面两个文件存在
            params.put(SpeechSynthesizer.PARAM_TTS_TEXT_MODEL_FILE, offlineResource.getTextFilename());
            params.put(SpeechSynthesizer.PARAM_TTS_SPEECH_MODEL_FILE, offlineResource.getModelFilename());
        }
        return params;
    }

    protected OfflineResource createOfflineResource(String voiceType) {
        OfflineResource offlineResource = null;
        try {
            offlineResource = new OfflineResource(this, voiceType);
        } catch (IOException e) {
            // IO 错误自行处理
            e.printStackTrace();
            toPrint("【error】:copy files from assets failed." + e.getMessage());
        }
        return offlineResource;
    }

    protected InitConfig getInitConfig(SpeechSynthesizerListener listener) {
        Map<String, String> params = getParams();
        // 添加你自己的参数
        InitConfig initConfig;
        // appId appKey secretKey 网站上您申请的应用获取。注意使用离线合成功能的话，需要应用中填写您app的包名。包名在build.gradle中获取。
        if (sn == null) {
            initConfig = new InitConfig(appId, appKey, secretKey, ttsMode, params, listener);
        } else {
            initConfig = new InitConfig(appId, appKey, secretKey, sn, ttsMode, params, listener);
        }
        // 如果您集成中出错，请将下面一段代码放在和demo中相同的位置，并复制InitConfig 和 AutoCheck到您的项目中
        // 上线时请删除AutoCheck的调用
        AutoCheck.getInstance(getApplicationContext()).check(initConfig, mainHandler);
        return initConfig;
    }

    /**
     * speak 实际上是调用 synthesize后，获取音频流，然后播放。
     * 获取音频流的方式见SaveFileActivity及FileSaveListener
     * 需要合成的文本text的长度不能超过1024个GBK字节。
     */
    private void speak(String text) {
        // 需要合成的文本text的长度不能超过1024个GBK字节。
        if (TextUtils.isEmpty(text)) {
            return;
        }
        // 合成前可以修改参数：
        // Map<String, String> params = getParams();
        // params.put(SpeechSynthesizer.PARAM_SPEAKER, "3"); // 设置为度逍遥
        // synthesizer.setParams(params);
        int result = synthesizer.speak(text);
        checkResult(result, "speak");
    }

    private void checkResult(int result, String method) {
        if (result != 0) {
            toPrint("error code :" + result + " method:" + method);
        }
    }

    private void toPrint(String text) {
        Log.d(TAG, text);
    }

}
