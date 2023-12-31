package telnet.com.backend.core.manager;

import javafx.application.Platform;
import telnet.com.backend.core.AutoTaskThread;
import telnet.com.backend.core.TelnetThread;
import telnet.com.backend.entity.Monitor;
import telnet.com.backend.entity.SystemConfig;
import telnet.com.backend.util.DateUtil;
import telnet.com.backend.util.LogImpl;
import telnet.com.backend.util.TelnetUtil;
import telnet.com.view.components.HomeComponent;
import telnet.com.view.components.LogComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;

/**
 * Telnet控制管理器实现类。
 * <p>
 * @author: cw
 * @since: 2023/7/27 12:52
 * @version: v0.1
 * <p>
 * 修改记录：
 * 时间      修改人员    修改内容
 * ------------------------------
 */
public class TelnetControlManagerImpl implements TelnetControlManager {

    private final MonitorManager monitorManager;
    private final SystemConfig systemConfig;

    private final TelnetThread telnetThread;
    private final Thread synctelnetThread;
    private final AutoTaskThread autoTaskThread;
    private final Timer taskTimer;


    public TelnetControlManagerImpl(SystemConfig systemConfig, MonitorManager monitorManager,
                                    TelnetThread telnetThread, Thread synctelnetThread, AutoTaskThread autoTaskThread, Timer taskTimer) {
        this.monitorManager = monitorManager;
        this.systemConfig = systemConfig;
        this.telnetThread = telnetThread;
        this.synctelnetThread = synctelnetThread;
        this.autoTaskThread = autoTaskThread;
        this.taskTimer = taskTimer;
    }

    @Override
    public void initAutoTask() {

        // 30 毫秒后启动执行，之后每间隔 timeInterval 毫秒执行
        taskTimer.schedule(autoTaskThread, 300, systemConfig.getTimeInterval());
    }

    @Override
    public void cancelAutoTask() {
        taskTimer.cancel();
    }

    @Override
    public void runTelnetThread() {

        // 获取状态
        Thread.State state = synctelnetThread.getState();
        if (state.equals( Thread.State.NEW )) {
            // 启动线程
            synctelnetThread.start();
        } else if (state.equals(Thread.State.RUNNABLE) && !telnetThread.isWaitStatus()){
            // 设置线程状态为执行
            telnetThread.setWaitStatus(true);
        } else {
            // 添加面板信息 Platform.runLater(()-> LogComponent.show( "线程繁忙... 让它缓一会在点" ));
            LogComponent.show("[sys]: 线程繁忙... 让它缓一会在点" );
        }
    }

    @Override
    public void telnet() {

        // 当线程资源状态为 false 的时候不执行
        if (!monitorManager.getResourceStatus()){

            LogImpl.info("occupancy of resources");
            LogComponent.print( "[sys]: 资源正在被其他线程使用，此线程本次不执行" );
            return;
        }

        monitorManager.lock();

        try {

            List<Monitor> monitorList = monitorManager.getMonitorList();
            if (monitorList.isEmpty()) {
                LogComponent.print( "[sys]: 还没有监控数据！快去配置管理里配置吧~" );
                return;
            }


            // 声明创建用于保存telnet 异常监控对象，使用于弹出层提示
            List<Monitor> errMonitor = new ArrayList<>();
            // 遍历监控数据，循环执行 telnet. 使用线程数据同步的方式执行 MonitorManager.getMonitorList()
            for (Monitor monitor : monitorList) {

                // 调用 telnet 实现工具类执行 telnet 监听
                boolean result = TelnetUtil.telnet(monitor.getHostname(), monitor.getPort(), systemConfig.getTimeout());
                // 验证 telnet 执行结果，判定 当前IP PORT是否正常
                if ( !result ) {
                    // 对异常IP PORT进行累加异常记录值
                    monitor.setErrNumber( monitor.getErrNumber() + 1 );
                    // 将此次 telnet 异常数据添加到异常集合中
                    errMonitor.add(monitor);
                }
                // 累加telnet 总数据
                monitor.setCountNumber(monitor.getCountNumber() + 1 );
                // 将 telnet 信息输入日志
                LogImpl.info( LogImpl.LOG_INFO, monitor.getHostname(), monitor.getPort().toString(), result);
            }
            LogImpl.info("telnet run end");

            // 持久化
            monitorManager.dataPersistence();

            // 判断是否有监控异常信息 没有结束方法
            if (errMonitor.isEmpty()) {
                return;
            }

            // 发现异常监控对象集合中有数据，准备组装异常信息调用弹窗，提示异常信息
            StringBuffer buffer = new StringBuffer("telnet err >>>");
            for (Monitor monitor : errMonitor) {
                buffer.append("\n\t").append(monitor.getHostname()).append("\t").append(monitor.getPort());
            }

            Platform.runLater(() -> {

                HomeComponent.ALERT.setTitle("telnet 监控程序");
                HomeComponent.ALERT.setHeaderText("监控发现异常 IP Port !\n" + DateUtil.getTime());
                HomeComponent.ALERT.setContentText(buffer.toString());
                HomeComponent.ALERT.show();
            });

        } finally {
            monitorManager.unlock();
        }
    }
}
