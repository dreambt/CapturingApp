package org.fosstrak.capturingapp;

import org.apache.log4j.Logger;
import org.fosstrak.capturingapp.util.CaptureAppWorker;
import org.fosstrak.capturingapp.wsdl.ArrayOfString;
import org.fosstrak.capturingapp.wsdl.CaptureAppPortType;
import org.fosstrak.capturingapp.wsdl.EmptyParms;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * entry point for the Fosstrak capturing application service. the service has been
 * implemented as a fully blown WS-service. Therefore if someone likes to
 * implement the service with an interface providing creation, deletion and
 * modification of capture applications, feel free to go ahead...<br/>
 * The service currently exports the names of the capture applications.
 */
public class CaptureAppPortTypeImpl implements CaptureAppPortType {

    // the executor thread pool.
    private static ExecutorService pool = java.util.concurrent.
            Executors.newCachedThreadPool();

    // a hash map maintaining the different capture applications.
    private static Map<String, CaptureAppWorker> captureApps = new ConcurrentHashMap<String, CaptureAppWorker>();

    // logger.
    private static final Logger log = Logger.getLogger(CaptureAppPortTypeImpl.class);

    /**
     * the name of the configuration file.
     */
    public static final String CONFIG_FILE = "/captureapplication.properties";

    /**
     * the class name of the default handler.
     */
    public static final String DEFAULT_HANDLER_CLASS_NAME =
            "org.fosstrak.capturingapp.DefaultECReportHandler";

    // flag whether the capture app is initialized or not.
    private static boolean initialized = false;

    /**
     * create a new capture application port type.
     *
     * @throws Exception upon some exception.
     */
    public CaptureAppPortTypeImpl() throws Exception {
        initialize();
    }

    /**
     * submit a runnable to the thread pool and execute it.
     *
     * @param runnable the runnable to execute.
     * @return a future value with a handle on the executor.
     */
    @SuppressWarnings("unchecked")
    public static Future submitToThreadPool(Runnable runnable) {
        return pool.submit(runnable);

    }

    /**
     * initialize the WS.
     *
     * @throws Exception when the configuration file could not be found or
     *                   if there is an error in the configuration.
     */
    private static void initialize() throws Exception {
        if (initialized) {
            log.error("已经初始化过.");
            return;
        }

        log.info("初始化 CaptureApp");

        Properties props = new Properties();
        try {
            props.load(
                    CaptureAppPortTypeImpl.class.getResourceAsStream(
                            CONFIG_FILE));

            final int n = Integer.parseInt(props.getProperty("n"));
            // create capture apps for all the configurations...
            for (int i = 0; i < n; i++) {

                final int port = Integer.parseInt(
                        props.getProperty("cap." + i + ".port", "-1"));

                final String name = props.getProperty(
                        "cap." + i + ".name", "cap." + i + ".name");

                final String epcis = props.getProperty(
                        "cap." + i + ".epcis", "tcp://localhost:1234");

                final String changeSet = props.getProperty(
                        "cap." + i + ".changeset", null);

                String handlerClzzName = props.getProperty(
                        "cap." + i + ".handler", null);

                log.info(String.format("创建新的 CaptureApp: (%s,%d,%s)",
                        name, port, epcis));
                captureApps.put(name, new CaptureAppWorker(
                        name,
                        new org.fosstrak.capturingapp.CaptureApp(port,
                                epcis)));

                if (null == handlerClzzName) {
                    handlerClzzName = DEFAULT_HANDLER_CLASS_NAME;
                }
                log.info("处理类: " + handlerClzzName);

                try {
                    Class cls = Class.forName(handlerClzzName);
                    Object obj = null;
                    if (null == changeSet) {
                        obj = cls.newInstance();
                    } else {
                        log.debug(String.format("变更集: %s", changeSet));
                        Constructor ctor = cls.getConstructor(String.class);
                        obj = ctor.newInstance(changeSet);
                    }

                    if (obj instanceof ECReportsHandler) {
                        captureApps.get(name).getCaptureApp().
                                registerHandler((ECReportsHandler) obj);

                    } else {
                        throw new Exception("无效类型: " + obj.getClass());
                    }
                } catch (Exception e) {
                    log.error("无法创建处理句柄: " + e.getMessage());
                }
            }

            // start the capture apps
            for (CaptureAppWorker worker : captureApps.values()) {
                log.info(String.format("开启 CaptureApp: (%s,%d,%s)",
                        worker.getIdentifier(),
                        worker.getCaptureApp().getPort(),
                        worker.getCaptureApp().getEpcisRepositoryURL()));
                worker.start();
            }

        } catch (IOException e) {
            log.error("无法加载配置文件.");
            e.printStackTrace();
            initialized = false;
            throw e;
        }

        initialized = true;
    }

    // --------- \\ WS definition

    /* (non-Javadoc)
    * @see org.fosstrak.captureapp.wsdl.CaptureAppPortType#getCaptureAppNames(org.fosstrak.captureapp.wsdl.EmptyParms  parms )*
    */
    public org.fosstrak.capturingapp.wsdl.ArrayOfString getCaptureAppNames(EmptyParms parms) {
        ArrayOfString aos = new ArrayOfString();
        for (CaptureAppWorker worker : captureApps.values()) {
            aos.getString().add(worker.getIdentifier());
        }
        return aos;
    }

    // --------- \\ end of WS definition

    protected void finalize() throws Throwable {
        log.info("调用回收器.");
        for (CaptureAppWorker worker : captureApps.values()) {
            worker.stop();
        }
        super.finalize();
    }
}