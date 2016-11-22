package wang.fly.com.yunhealth.Fragments;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import wang.fly.com.yunhealth.Adapter.RecycleAdapterForMeasure;
import wang.fly.com.yunhealth.R;
import wang.fly.com.yunhealth.Activity.ShowHeartWaves;
import wang.fly.com.yunhealth.util.ClsUtils;
import wang.fly.com.yunhealth.util.UtilClass;

import static android.R.attr.breadCrumbShortTitle;
import static android.R.attr.cacheColorHint;
import static android.R.attr.filter;
import static android.R.attr.hand_hour;
import static android.R.attr.process;
import static android.R.attr.switchMinWidth;
import static android.R.attr.track;
import static android.R.id.message;
import static android.app.Activity.RESULT_OK;
import static android.content.Context.INPUT_METHOD_SERVICE;
import static java.security.AccessController.getContext;

/*
 * Created by 兆鹏 on 2016/11/2.
 */
public class MeasureFragment extends Fragment implements View.OnClickListener {
    private TextView connectDevice;
    private RecyclerView recyclerView;
    private GridLayoutManager gridLayoutManager;
    private Context context;
    private RecycleAdapterForMeasure myAdapter;
    private ProgressBar load;
    private boolean isTryingConnecting = false;
    String[] titleArrays = {"血氧\n34>=44", "心电\n442>=232", "体重体脂", "血糖",
            "体温", "粉尘", "脑电（待定）", "血压（待定）"};
    int[] idArrays = {R.drawable.bloodoxygen, R.drawable.heartwaves, R.drawable.weight, R.drawable.bloodsugar,
            R.drawable.temperature, R.drawable.dirty, R.drawable.headwaves, R.drawable.bloodpress};
    int[] colorArrays = {R.color.bg1, R.color.bg2, R.color.bg3, R.color.bg4, R.color.bg5, R.color.bg6,
            R.color.bg7, R.color.bg8};
    boolean[] stateArrays = {
            true, true, false, false, false, false, false, false
    };
    private final static String deviceAddress = "98:D3:32:70:5A:44";
    private static final String TAG = "MeasureFragment";
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice theDestDevice;
    BroadcastReceiver receiver;
    private ClientThread clientThread;
    private ConnectedThread connectThread;
    List<Map<String, Object>> datas = new ArrayList<>();
    //请求
    static final int REQUEST_OPEN_BLUETOOTH = 0;
    static final int MSG_WAIT_CONNECT = 0;
    static final int MSG_CONNECT_SUCCESS = 1;
    static final int MSG_START_CONNECT = 2;
    static final int MSG_READ_STRING = 3;
    static final int MSG_CONNECT_FAILED = 4;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.measurefragment_layout, container, false);
        context = getContext();
        findView(v);
        registerBroadReceiver();
        return v;
    }

    private void registerBroadReceiver() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                switch (action) {
                    case BluetoothDevice.ACTION_FOUND: {
                                          /* 从intent中取得搜索结果数据 */
                        BluetoothDevice device = intent
                                .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (TextUtils.equals(device.getAddress(), deviceAddress)) {
                            theDestDevice = device;
                            if (theDestDevice.getBondState() == BluetoothDevice.BOND_NONE) {
                                //没有进行过配对
                                try {
                                    ClsUtils.createBond(theDestDevice.getClass(), theDestDevice);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            } else if (theDestDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
                                //已经配对过，进行连接

                            }
                        }
                        Log.d(TAG, "设备：" + device.getName() + " address: " + device.getAddress());
                        break;
                    }
                    case BluetoothDevice.ACTION_PAIRING_REQUEST: {
                        Log.d(TAG, "onReceive: 进行配对");
                        try {
                            boolean ret = ClsUtils.setPin(theDestDevice.getClass(), theDestDevice, "1234");
                            //1.确认配对
                            ClsUtils.setPairingConfirmation(theDestDevice.getClass(), theDestDevice, true);
                            //2.终止有序广播
                            abortBroadcast();//如果没有将广播终止，则会出现一个一闪而过的配对框。
                            //3.调用setPin方法进行配对...

                        } catch (Exception e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                        //终止广播
                        abortBroadcast();
                        break;
                    }
                    case BluetoothDevice.ACTION_BOND_STATE_CHANGED: {
                        theDestDevice = bluetoothAdapter.getRemoteDevice(theDestDevice.getAddress());
                        if (theDestDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
                            Log.d(TAG, "onReceive: 配对成功");
                            clientThread = new ClientThread();
                            isTryingConnecting = true;
                            clientThread.start();
                        } else if (theDestDevice.getBondState() == BluetoothDevice.BOND_NONE) {
                            Log.d(TAG, "onReceive: 没有配对");
                        } else if (theDestDevice.getBondState() == BluetoothDevice.BOND_BONDING) {
                            Log.d(TAG, "onReceive: 正在配对");
                        }
                        break;
                    }
                    case BluetoothDevice.ACTION_ACL_CONNECTED: {

                        break;
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        getContext().registerReceiver(receiver, filter);
        filter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        getContext().registerReceiver(receiver, filter);
        filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        getContext().registerReceiver(receiver, filter);
        filter = new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED);
        getContext().registerReceiver(receiver, filter);
    }

    private void findView(View v) {
        recyclerView = (RecyclerView) v.findViewById(R.id.measure_recycleView);
        connectDevice = (TextView) v.findViewById(R.id.connect_device);
        load = (ProgressBar) v.findViewById(R.id.load);
        load.setVisibility(View.INVISIBLE);
        connectDevice.setOnClickListener(this);

        gridLayoutManager = new GridLayoutManager(context, 2);
        recyclerView.setLayoutManager(gridLayoutManager);
        recyclerView.setHasFixedSize(true);
        Observable.create(new Observable.OnSubscribe<List<Map<String, Object>>>() {
            @Override
            public void call(Subscriber<? super List<Map<String, Object>>> subscriber) {
                try {
                    for (int i = 0; i < titleArrays.length; i++) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("text", titleArrays[i]);
                        data.put("id", idArrays[i]);
                        data.put("color", colorArrays[i]);
                        data.put("isDanger", stateArrays[i]);
                        datas.add(data);
                    }
                    subscriber.onNext(datas);
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Subscriber<List<Map<String, Object>>>() {
                    @Override
                    public void onCompleted() {

                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onNext(List<Map<String, Object>> maps) {
                        myAdapter = new RecycleAdapterForMeasure(maps,
                                R.layout.measure_recycle_item_layout, getContext());
                        recyclerView.setAdapter(myAdapter);
                        myAdapter.setOnItemClickListener(new RecycleAdapterForMeasure.OnItemClickListener() {
                            @Override
                            public void onItemClick(View view, int position) {
                                switch (position) {
                                    case 1: {
                                        //点击心电图的子项
                                        Toast.makeText(context, "打开心电图", Toast.LENGTH_SHORT).show();
                                        Intent intent = new Intent(context, ShowHeartWaves.class);
                                        context.startActivity(intent);
                                        break;
                                    }
                                    default:
                                        Toast.makeText(context, "暂无更多", Toast.LENGTH_SHORT).show();

                                }
                            }
                        });
                    }
                });
    }

    /**
     * 请求获取用户粗略定位的权限
     * android 6.0及其以上使用
     */
    private void mayRequestLocation() {
        if (Build.VERSION.SDK_INT >= 23) {
            int check = getContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (check != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, 0);
            }
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.connect_device: {
                //连接设备
                connectDevice.setVisibility(View.INVISIBLE);
                connectDevice.setClickable(false);
                load.setVisibility(View.VISIBLE);
                mayRequestLocation();
                openBluetooth();
                break;
            }
        }
    }

    private void toToast(String content) {
        Toast.makeText(getContext(), content, Toast.LENGTH_SHORT).show();
    }

    /**
     * 打开蓝牙
     */
    private void openBluetooth() {
        //判断蓝牙的打开状态
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            toToast("本机不支持蓝牙设备");
            System.exit(0);
        } else if (!bluetoothAdapter.isEnabled()) {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(intent, REQUEST_OPEN_BLUETOOTH);
        } else {
            searchBluetoothDevice();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_OPEN_BLUETOOTH: {
                if (resultCode == RESULT_OK) {
                    //搜索蓝牙设备
                    searchBluetoothDevice();
                } else {
                    toToast("未打开蓝牙，故无法测量实时数据");
                }
                break;
            }
        }
    }

    /**
     * 搜索蓝牙设备，添加到显示页面
     */
    private void searchBluetoothDevice() {
        Set<BluetoothDevice> bondedDevice = bluetoothAdapter.getBondedDevices();
        Iterator<BluetoothDevice> it = bondedDevice.iterator();
        boolean flag = false;
        while (it.hasNext()) {
            BluetoothDevice blue = it.next();
            Log.d(TAG, "onActivityResult: name: " + blue.getName());
            Log.d(TAG, "onActivityResult: address: " + blue.getAddress());
            if (blue.getAddress().equals(deviceAddress)) {
                flag = true;
                break;
            }
        }
        if (flag) {
            //已经配对设备，直接进行链接
            Log.d(TAG, "onActivityResult: 已经配对，直接进行连接");
            clientThread = new ClientThread();
            isTryingConnecting = true;
            clientThread.start();
        } else {
            //搜索蓝牙进行配对
            if (bluetoothAdapter.isDiscovering()) {//正在查找
                Log.d(TAG, "onClick: 正在查找设备");
            } else {
                bluetoothAdapter.startDiscovery();
            }
        }

    }

    private class ClientThread extends Thread {
        private BluetoothSocket bluetoothSocket;

        public ClientThread() {
            theDestDevice = bluetoothAdapter.getRemoteDevice(deviceAddress);
            try {
                bluetoothSocket = theDestDevice.createRfcommSocketToServiceRecord(
                        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
                Log.d(TAG, "ClientThread: 构造socket");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            bluetoothAdapter.cancelDiscovery();

            Message message = Message.obtain();
            if (bluetoothSocket != null) {
                try {
                    bluetoothSocket.connect();
                    Log.d(TAG, "run: connected" + bluetoothSocket.isConnected());
                } catch (IOException e) {
                    Log.e(TAG, "run: ", e);
                    e.printStackTrace();
                    initConnect();
                }
                message.what = bluetoothSocket.isConnected() ? MSG_CONNECT_SUCCESS : MSG_CONNECT_FAILED;
                handler.sendMessage(message);
            }
        }

        public void cancel() {
            try {
                bluetoothSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public BluetoothSocket getBluetoothSocket() {
            return bluetoothSocket;
        }

        public void setBluetoothSocket(BluetoothSocket bluetoothSocket) {
            this.bluetoothSocket = bluetoothSocket;
        }
    }

    public void initConnect() {
        if (clientThread != null) {
            clientThread.cancel();
            clientThread = null;
        }
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
    }

    private class ConnectedThread extends Thread {
        private BluetoothSocket bluetoothSocket;
        private OutputStream outputStream;
        private InputStream inputStream;
        byte[] bytes = new byte[512];
        private boolean flag = false;

        public boolean isFlag() {
            return flag;
        }

        public void setFlag(boolean flag) {
            this.flag = flag;
        }

        public ConnectedThread(BluetoothSocket bluetoothSocket) {
            this.bluetoothSocket = bluetoothSocket;
            try {
                outputStream = this.bluetoothSocket.getOutputStream();
                inputStream = this.bluetoothSocket.getInputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        @Override
        public void run() {
            String data = "";
            while (flag) {
                if (inputStream == null)
                    return;
                try {
                    inputStream.read(bytes);
                    data = resolveData(UtilClass.valueOfBytes(bytes) + data);
                    Log.d(TAG, "run: receive" + data);
                    Log.d(TAG, "run: connet" + bluetoothSocket.isConnected());
                } catch (IOException e) {
                    cancel();
                    Message message = Message.obtain();
                    message.what = MSG_CONNECT_FAILED;
                    handler.sendMessage(message);
                    e.printStackTrace();
                }
            }

        }

        private String resolveData(String data) {
            int start = 0;
            int end = 0;
            while (start != -1 && end != -1) {
                start = data.indexOf("dcba");
                if (start != -1 && data.length() > 4) {
                    end = data.indexOf("dcba", start + 1);
                }
                if (start != -1 && end != -1 && start != end) {
                    analysisData(data.substring(start, end));
                    data = data.substring(end);
                }
            }
            return data;
        }

        /**
         * 处理的字符串应该符合如下规则
         * dcba + e[0~9] + [0~9][0~9] + [0~9][0~9] + ~ + sum
         *
         * @param substring
         */
        private boolean analysisData(String substring) {
            //字符串的长度
            if (substring == null) {
                return false;
            }
            int len = substring.length();
            if (len < 12) {
                return false;
            }
            if (!UtilClass.checkHexString(substring)) {
                return false;
            }
            if (!substring.startsWith("dcbae")) {
                return false;
            }
            int type = Integer.valueOf(substring.substring(5, 6), 16);
            int highLength = Integer.valueOf(substring.substring(6, 8), 16);
            int lowLength = Integer.valueOf(substring.substring(8, 10), 16);
            if (len != (12 + (highLength + lowLength) * 2)) {
                //长度不符合
                return false;
            }
            int sum = 0;
            for (int i = 2; i < len; ) {
                sum += Integer.valueOf(substring.substring(i - 2, i), 16);
                i += 2;
            }
            String sumString = Integer.toHexString(sum);
            if (!sumString.substring(sumString.length() - 2, sumString.length())
                    .equals(substring.substring(substring.length() - 2, substring.length()))) {
                return false;
            }
            String dataString = substring.substring(10, 10 + (highLength + lowLength) * 2);
            if (dataString.length() <= 0) {
                return false;
            }
            Message message = Message.obtain();
            message.what = MSG_READ_STRING;
            message.arg1 = type;
            message.obj = dataString;
            handler.sendMessage(message);
            return true;
        }

        public void write(String string) {
            byte[] bytes;
            bytes = string.getBytes();
            try {
                outputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void cancel() {
            this.flag = false;
            try {
                bluetoothSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case MSG_CONNECT_FAILED: {
                    Log.d(TAG, "handleMessage: 连接失败");
                    connectDevice.setClickable(true);
                    connectDevice.setText("连接失败");
                    connectDevice.setVisibility(View.VISIBLE);
                    load.setVisibility(View.INVISIBLE);
                    break;
                }
                case MSG_CONNECT_SUCCESS: {
                    Log.d(TAG, "handleMessage: 连接成功");
                    connectDevice.setText("已连接");
                    connectDevice.setVisibility(View.VISIBLE);
                    load.setVisibility(View.INVISIBLE);
                    connectDevice.setClickable(false);
                    connectThread = new ConnectedThread(clientThread.getBluetoothSocket());
                    connectThread.setFlag(true);
                    connectThread.setDaemon(true);
                    connectThread.start();
                    break;
                }
                case MSG_READ_STRING: {
                    //进行数据的分类设定
                    String data = (String) msg.obj;
                    switch (msg.arg1) {
                        //每次都要求获取数据的字符串
                        case 0: {
                            //保留
                            break;
                        }
                        case 1: {
                            //血氧
                            if (data.length() == 4){
                                titleArrays[0] = "血糖\n" + Integer.valueOf((int) data.charAt(0));
                            }
                            break;
                        }
                        case 2: {
                            //心电
                            break;
                        }
                        case 3: {
                            //血糖
                            break;
                        }
                        case 4: {
                            //体温
                            Log.d("handler", "handleMessage: 体温" + data);
                            if (data.length() == 4){
                                titleArrays[4] = "体温\n" + Integer.valueOf(data, 16)/ 100;
                                int i = 4;
                                Map<String, Object> data2 = new HashMap<>();
                                data2.put("text", titleArrays[i]);
                                data2.put("id", idArrays[i]);
                                data2.put("color", colorArrays[i]);
                                data2.put("isDanger", stateArrays[i]);
                                datas.set(4, data2);
                                Log.d("handler", "handleMessage: " + titleArrays[4]);
                                myAdapter.notifyItemChanged(4);
                            }
                            break;
                        }
                        case 5: {
                            //粉尘
                            break;
                        }
                        case 6: {
                            //脑电（待定）
                            break;
                        }
                        case 7: {
                            //血压（待定）
                            break;
                        }
                        case 8: {
                            break;
                        }
                        case 9: {
                            break;
                        }
                        case 10: {
                            break;
                        }
                        case 11: {
                            break;
                        }
                        case 12: {
                            break;
                        }
                        case 13: {
                            break;
                        }
                        case 14: {
                            break;
                        }
                        case 15: {
                            break;
                        }
                    }
//                    myAdapter.notifyDataSetChanged();
                }
            }
        }
    };

    @Override
    public void onDestroyView() {
        getContext().unregisterReceiver(receiver);
        isTryingConnecting = false;
        initConnect();
        super.onDestroyView();
    }
}