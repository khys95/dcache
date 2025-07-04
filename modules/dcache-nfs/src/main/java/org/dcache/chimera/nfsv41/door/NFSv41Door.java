package org.dcache.chimera.nfsv41.door;

import static com.google.common.net.InetAddresses.toAddrString;
import static dmg.util.CommandException.checkCommand;
import static java.util.stream.Collectors.toList;
import static org.dcache.chimera.nfsv41.door.ExceptionUtils.asNfsException;
import static org.dcache.util.TransferRetryPolicy.alwaysRetry;

import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import com.google.common.net.InetAddresses;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import diskCacheV111.namespace.EventNotifier;
import diskCacheV111.util.CacheException;
import diskCacheV111.util.FileNotFoundCacheException;
import diskCacheV111.util.FileNotInCacheException;
import diskCacheV111.util.FsPath;
import diskCacheV111.util.PermissionDeniedCacheException;
import diskCacheV111.util.PnfsHandler;
import diskCacheV111.util.PnfsId;
import diskCacheV111.vehicles.DoorRequestInfoMessage;
import diskCacheV111.vehicles.DoorTransferFinishedMessage;
import diskCacheV111.vehicles.IoDoorEntry;
import diskCacheV111.vehicles.IoDoorInfo;
import diskCacheV111.vehicles.Pool;
import diskCacheV111.vehicles.PoolMoverKillMessage;
import diskCacheV111.vehicles.PoolPassiveIoFileMessage;
import diskCacheV111.vehicles.PoolStatusChangedMessage;
import dmg.cells.nucleus.AbstractCellComponent;
import dmg.cells.nucleus.CDC;
import dmg.cells.nucleus.CellAddressCore;
import dmg.cells.nucleus.CellCommandListener;
import dmg.cells.nucleus.CellInfoProvider;
import dmg.cells.nucleus.CellMessageReceiver;
import dmg.cells.nucleus.CellPath;
import dmg.cells.services.login.LoginBrokerPublisher;
import dmg.cells.services.login.LoginManagerChildrenInfo;
import dmg.util.CommandException;
import dmg.util.command.Argument;
import dmg.util.command.Command;
import dmg.util.command.Option;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.concurrent.GuardedBy;
import javax.security.auth.Subject;

import eu.emi.security.authn.x509.CrlCheckingMode;
import eu.emi.security.authn.x509.OCSPCheckingMode;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import org.dcache.alarms.AlarmMarkerFactory;
import org.dcache.alarms.PredefinedAlarm;
import org.dcache.auth.Subjects;
import org.dcache.auth.attributes.Restrictions;
import org.dcache.cells.CellStub;
import org.dcache.cells.CuratorFrameworkAware;
import org.dcache.chimera.ChimeraFsException;
import org.dcache.chimera.FsInode;
import org.dcache.chimera.FsInodeType;
import org.dcache.chimera.JdbcFs;
import org.dcache.chimera.nfsv41.common.LegacyUtils;
import org.dcache.chimera.nfsv41.common.StatsDecoratedOperationExecutor;
import org.dcache.chimera.nfsv41.door.proxy.NfsProxyIoFactory;
import org.dcache.chimera.nfsv41.door.proxy.ProxyIoFactory;
import org.dcache.chimera.nfsv41.mover.NFS4ProtocolInfo;
import org.dcache.commons.stats.RequestExecutionTimeGauges;
import org.dcache.http.JdkSslContextFactory;
import org.dcache.namespace.FileAttribute;
import org.dcache.nfs.ChimeraNFSException;
import org.dcache.nfs.ExportFile;
import org.dcache.nfs.FsExport;
import org.dcache.nfs.InetAddressMatcher;
import org.dcache.nfs.nfsstat;
import org.dcache.nfs.status.BadHandleException;
import org.dcache.nfs.status.BadLayoutException;
import org.dcache.nfs.status.BadStateidException;
import org.dcache.nfs.status.DelayException;
import org.dcache.nfs.status.LayoutTryLaterException;
import org.dcache.nfs.status.LayoutUnavailableException;
import org.dcache.nfs.status.NfsIoException;
import org.dcache.nfs.status.NoMatchingLayoutException;
import org.dcache.nfs.status.PermException;
import org.dcache.nfs.status.ServerFaultException;
import org.dcache.nfs.status.StaleException;
import org.dcache.nfs.status.StaleStateidException;
import org.dcache.nfs.status.UnknownLayoutTypeException;
import org.dcache.nfs.v3.MountServer;
import org.dcache.nfs.v3.NfsServerV3;
import org.dcache.nfs.v3.xdr.mount_prot;
import org.dcache.nfs.v3.xdr.nfs3_prot;
import org.dcache.nfs.v4.ClientCB;
import org.dcache.nfs.v4.ClientRecoveryStore;
import org.dcache.nfs.v4.CompoundContext;
import org.dcache.nfs.v4.FlexFileLayoutDriver;
import org.dcache.nfs.v4.Layout;
import org.dcache.nfs.v4.LayoutDriver;
import org.dcache.nfs.v4.NFS4Client;
import org.dcache.nfs.v4.NFS4State;
import org.dcache.nfs.v4.NFSServerV41;
import org.dcache.nfs.v4.NFSv41DeviceManager;
import org.dcache.nfs.v4.NFSv41Session;
import org.dcache.nfs.v4.NFSv4Defaults;
import org.dcache.nfs.v4.NFSv4StateHandler;
import org.dcache.nfs.v4.Stateids;
import org.dcache.nfs.v4.ff.ff_ioerr4;
import org.dcache.nfs.v4.ff.ff_layoutreturn4;
import org.dcache.nfs.v4.ff.flex_files_prot;
import org.dcache.nfs.v4.xdr.GETDEVICEINFO4args;
import org.dcache.nfs.v4.xdr.GETDEVICELIST4args;
import org.dcache.nfs.v4.xdr.LAYOUTCOMMIT4args;
import org.dcache.nfs.v4.xdr.LAYOUTERROR4args;
import org.dcache.nfs.v4.xdr.LAYOUTGET4args;
import org.dcache.nfs.v4.xdr.LAYOUTRETURN4args;
import org.dcache.nfs.v4.xdr.LAYOUTSTATS4args;
import org.dcache.nfs.v4.xdr.clientid4;
import org.dcache.nfs.v4.xdr.device_addr4;
import org.dcache.nfs.v4.xdr.device_error4;
import org.dcache.nfs.v4.xdr.deviceid4;
import org.dcache.nfs.v4.xdr.layout4;
import org.dcache.nfs.v4.xdr.layoutiomode4;
import org.dcache.nfs.v4.xdr.layoutreturn_type4;
import org.dcache.nfs.v4.xdr.layouttype4;
import org.dcache.nfs.v4.xdr.length4;
import org.dcache.nfs.v4.xdr.nfs4_prot;
import org.dcache.nfs.v4.xdr.nfs_fh4;
import org.dcache.nfs.v4.xdr.nfs_opnum4;
import org.dcache.nfs.v4.xdr.offset4;
import org.dcache.nfs.v4.xdr.stateid4;
import org.dcache.nfs.v4.xdr.utf8str_mixed;
import org.dcache.nfs.vfs.Inode;
import org.dcache.nfs.vfs.Stat;
import org.dcache.nfs.vfs.VfsCache;
import org.dcache.nfs.vfs.VfsCacheConfig;
import org.dcache.nfs.vfs.VirtualFileSystem;
import org.dcache.oncrpc4j.rpc.OncRpcProgram;
import org.dcache.oncrpc4j.rpc.OncRpcSvc;
import org.dcache.oncrpc4j.rpc.OncRpcSvcBuilder;
import org.dcache.oncrpc4j.rpc.gss.GssSessionManager;
import org.dcache.pool.assumption.Assumptions;
import org.dcache.poolmanager.CostException;
import org.dcache.poolmanager.PoolManagerStub;
import org.dcache.poolmanager.PoolMonitor;
import org.dcache.rquota.QuotaSvc;
import org.dcache.util.ByteUnit;
import org.dcache.util.CDCScheduledExecutorServiceDecorator;
import org.dcache.util.Exceptions;
import org.dcache.util.FireAndForgetTask;
import org.dcache.util.Glob;
import org.dcache.util.NDC;
import org.dcache.util.RedirectedTransfer;
import org.dcache.util.Transfer;
import org.dcache.util.TransferRetryPolicy;
import org.dcache.util.Version;
import org.dcache.vehicles.DoorValidateMoverMessage;
import org.dcache.vehicles.FileAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.kafka.core.KafkaTemplate;

public class NFSv41Door extends AbstractCellComponent implements
      NFSv41DeviceManager, CellCommandListener,
      CellMessageReceiver, CellInfoProvider, CuratorFrameworkAware {

    private static final Logger _log = LoggerFactory.getLogger(NFSv41Door.class);


    /**
     * Path to doors container within zookeeper
     */
    private static final String ZK_DOORS_PATH = "/dcache/nfs/doors";

    /**
     * Layout type specific driver.
     */
    private Map<layouttype4, LayoutDriver> _supportedDrivers;

    /**
     * A mapping between pool name, nfs device id and pool's ip addresses.
     */
    private final PoolDeviceMap _poolDeviceMap = new PoolDeviceMap();

    /**
     * Mapping between open state id and corresponding transfer.
     */
    private final Map<stateid4, NfsTransfer> _transfers = new ConcurrentHashMap<>();


    /**
     * Record that binds NFS client with a file handle.
     */
    public record OpenFile(clientid4 clientid, Inode inode) {}

    /**
     * NFS client+ file handle to layout stateid mapping.
     */
    private final Map<OpenFile, stateid4> layoutStates = new ConcurrentHashMap<>();

    /**
     * Maximal time the NFS request will blocked before we reply with NFSERR_DELAY. The usual
     * timeout for NFS operations is 30s. Nevertheless, as client's other requests will blocked as
     * well, we try to block as short as we can. The rule for interactive users: never block longer
     * than 10s.
     */
    private static final long NFS_REQUEST_BLOCKING = TimeUnit.SECONDS.toMillis(3);

    /**
     * A time duration that Transfer class will wait before retrying a request to a pool or pool
     * manager.
     */
    private static final long NFS_RETRY_PERIOD = TimeUnit.SECONDS.toMillis(10);

    /**
     * How long a pool selection request can hang around.
     * <p>
     * One week (7 days) is good enough to cover most of the public holidays.
     */
    private static final long MAX_REQUEST_LIFETIME = TimeUnit.DAYS.toMillis(7);

    /**
     * Amount of information logged by access logger.
     */
    private AccessLogMode _accessLogMode;

    /**
     * Cell communication helper.
     */
    private CellStub _poolStub;
    private PoolManagerStub _poolManagerStub;
    private CellStub _billingStub;
    private PnfsHandler _pnfsHandler;

    private PoolMonitor _poolMonitor;

    private String _ioQueue;

    /**
     * TCP port number to bind.
     */
    private int _port;

    /**
     * UDP port number used by remote quota protocol.
     */
    private int _rquotaPort;


    /**
     * nfs versions to run.
     */
    private Set<String> _versions;

    private static final String V3 = "3";
    private static final String V41 = "4.1";

    /**
     * embedded nfs server
     */
    private NFSServerV41 _nfs4;

    /**
     * NFS RPC service
     */
    private OncRpcSvc _rpcService;

    /**
     * Remote Quota RPC service
     */
    private OncRpcSvc _rquotaService;

    private StrategyIdMapper _idMapper;

    private boolean _enableRpcsecGss;

    private EventNotifier _eventNotifier;
    private VfsCache _vfsCache;
    private ChimeraVfs _chimeraVfs;
    private VirtualFileSystem _vfs;

    private LoginBrokerPublisher _loginBrokerPublisher;

    private ProxyIoFactory _proxyIoFactory;

    private Consumer<DoorRequestInfoMessage> _kafkaSender = (s) -> {
    };

    /**
     * Retry policy used for accessing files.
     */
    private static final TransferRetryPolicy POOL_SELECTION_RETRY_POLICY = alwaysRetry()
          .pauseBeforeRetrying(NFS_RETRY_PERIOD)
          .timeoutAfter(MAX_REQUEST_LIFETIME);

    private VfsCacheConfig _vfsCacheConfig;

    /**
     * {@link ExecutorService} used to issue call-backs to the client.
     */
    private final ScheduledExecutorService _callbackExecutor =
          new CDCScheduledExecutorServiceDecorator<>(Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder().setNameFormat("callback-%d").build()));

    /**
     * Exception thrown by transfer if accessed after mover have finished.
     */
    private static final ChimeraNFSException DELAY = new DelayException("Mover finished, EAGAIN");

    /**
     * Exception thrown by transfer if accessed after mover have finished.
     */
    private static final ChimeraNFSException POISON = new NfsIoException("Mover finished, EIO");

    /**
     * If true, door will query gplazma to overcome 16 sub-group limit by AUTH_SYS.
     */
    private boolean _manageGids;

    /**
     * NFSv4 operation executor with requests statistics.
     */
    private StatsDecoratedOperationExecutor _executor;

    /**
     * Handle to Zookeeper.
     */
    private CuratorFramework _curator;

    /**
     * Store for active client records.
     */
    private ClientRecoveryStore _clientStore;


    /**
     * Host certificate file.
     */
    private String _certFile;

    /**
     * Host private key file.
     */
    private String _keyFile;

    /**
     * Path to directory CA certificates
     */
    private String _caPath;

    /**
     * Enable RPC-over-TLS for NFS.
     */
    private boolean _enableTls;

    public void setEventNotifier(EventNotifier notifier) {
        _eventNotifier = notifier;
    }

    public void setEnableRpcsecGss(boolean enable) {
        _enableRpcsecGss = enable;
    }

    public void setIdMapper(StrategyIdMapper idMapper) {
        _idMapper = idMapper;
    }

    public void setPoolStub(CellStub stub) {
        _poolStub = stub;
    }

    public void setPoolManagerStub(PoolManagerStub stub) {
        _poolManagerStub = stub;
    }

    public void setPnfsHandler(PnfsHandler pnfs) {
        _pnfsHandler = pnfs;
    }

    private JdbcFs _fileFileSystemProvider;

    public void setFileSystemProvider(JdbcFs fs) {
        _fileFileSystemProvider = fs;
    }

    private ExportFile _exportFile;

    public void setExportFile(ExportFile export) {
        _exportFile = export;
    }

    public void setIoQueue(String ioQueue) {
        _ioQueue = ioQueue;
    }

    public void setPortNumber(int port) {
        _port = port;
    }

    public void setQuotaPortNumber(int port) {
        _rquotaPort = port;
    }

    public void setVersions(String[] versions) {
        _versions = Sets.newHashSet(versions);
    }

    @Required
    public void setLoginBrokerPublisher(LoginBrokerPublisher loginBrokerPublisher) {
        _loginBrokerPublisher = loginBrokerPublisher;
    }

    @Required
    public void setVfsCacheConfig(VfsCacheConfig vfsCacheConfig) {
        _vfsCacheConfig = vfsCacheConfig;
    }

    @Required
    public void setAccessLogMode(AccessLogMode accessLogMode) {
        _accessLogMode = accessLogMode;
    }

    @Autowired(required = false)
    public void setKafkaTemplate(KafkaTemplate kafkaTemplate) {
        _kafkaSender = kafkaTemplate::sendDefault;
    }

    @Autowired(required = false)
    public void setManageGroups(boolean manageGids) {
        _manageGids = manageGids;
    }

    @Required
    public void setClientStore(ClientRecoveryStore clientStore) {
        _clientStore = clientStore;
    }

    @Required
    public void setPoolMonitor(PoolMonitor poolMonitor) {
        _poolMonitor = poolMonitor;
    }

    public void setCertFile(String certFile) {
        _certFile = certFile;
    }

    public void setKeyFile(String keyFile) {
        _keyFile = keyFile;
    }

    public void setCaPath(String caPath) {
        _caPath = caPath;
    }

    public void setEnableTls(boolean enableTls) {
        _enableTls = enableTls;
    }

    public VirtualFileSystem wrapWithMonitoring(VirtualFileSystem inner) {
        MonitoringVfs monitor = new MonitoringVfs();
        monitor.setInner(inner);
        monitor.setFileSystemProvider(_fileFileSystemProvider);
        monitor.setEventReceiver(_eventNotifier);
        return monitor;
    }

    public void init() throws Exception {

        _chimeraVfs = new ChimeraVfs(_fileFileSystemProvider, _idMapper);
        var quotaSvc = new QuotaSvc(_chimeraVfs, _exportFile);
        _vfsCache = new VfsCache(_chimeraVfs, _vfsCacheConfig);
        _vfs = _eventNotifier == null ? _vfsCache : wrapWithMonitoring(_vfsCache);

        OncRpcSvcBuilder oncRpcSvcBuilder = new OncRpcSvcBuilder()
              .withPort(_port)
              .withTCP()
              .withAutoPublish()
              .withSubjectPropagation()
              .withWorkerThreadIoStrategy();

        if (_enableRpcsecGss) {
            oncRpcSvcBuilder.withGssSessionManager(new GssSessionManager(_idMapper));
        }

        if (_enableTls) {
            // FIXME: the certificate reload is not handled
            JdkSslContextFactory sslContextFactory = new JdkSslContextFactory();
            sslContextFactory.setServerCertificatePath(Path.of(_certFile));
            sslContextFactory.setServerKeyPath(Path.of(_keyFile));
            sslContextFactory.setServerCaPath(Path.of(_caPath));
            sslContextFactory.setOcspCheckingMode(OCSPCheckingMode.IGNORE);
            sslContextFactory.setCrlCheckingMode(CrlCheckingMode.IF_VALID);
            sslContextFactory.init();

            oncRpcSvcBuilder.withSSLContext(sslContextFactory.call());
            oncRpcSvcBuilder.withStartTLS();
        }

        for (String version : _versions) {
            switch (version) {
                case V3:
                    MountServer ms = new MountServer(_exportFile, _vfs);
                    NfsServerV3 nfs3 = new NfsServerV3(_exportFile, _vfs);
                    oncRpcSvcBuilder
                          .withRpcService(
                                new OncRpcProgram(nfs3_prot.NFS_PROGRAM, nfs3_prot.NFS_V3), nfs3)
                          .withRpcService(
                                new OncRpcProgram(mount_prot.MOUNT_PROGRAM, mount_prot.MOUNT_V3),
                                ms);
                    ;
                    _loginBrokerPublisher.setTags(Collections.emptyList());
                    break;
                case V41:
                    final NFSv41DeviceManager _dm = this;
                    _proxyIoFactory = new NfsProxyIoFactory(_dm);
                    _executor = new StatsDecoratedOperationExecutor(
                          new DoorOperationFactory(
                                _proxyIoFactory,
                                _chimeraVfs,
                                _fileFileSystemProvider,
                                _manageGids ? Optional.of(_idMapper)
                                      : Optional.empty(),
                                _accessLogMode)
                    );

                    int stateHandlerId = getOrCreateId(ZK_DOORS_PATH,
                          getCellName() + "@" + getCellDomainName(),
                          "state-handler-id");

                    NFSv4StateHandler stateHandler = new NFSv4StateHandler(
                          Duration.ofSeconds(NFSv4Defaults.NFS4_LEASE_TIME),
                          stateHandlerId,
                          _clientStore);

                    _nfs4 = new NFSServerV41.Builder()
                          .withStateHandler(stateHandler)
                          .withDeviceManager(_dm)
                          .withExportTable(_exportFile)
                          .withVfs(_vfs)
                          .withOperationExecutor(_executor)
                          .withImplementationName("dcache-" + Version.of(this.getClass()).getBuild())
                          .withImplementationDate(Instant.parse(Version.of(this.getClass()).getBuildTime()))
                          .build();

                    oncRpcSvcBuilder.withRpcService(
                          new OncRpcProgram(nfs4_prot.NFS4_PROGRAM, nfs4_prot.NFS_V4), _nfs4);
                    updateLbPaths();
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported NFS version: " + version);
            }
        }

        // Supported layout drivers
        _supportedDrivers = new EnumMap<>(layouttype4.class);
        _supportedDrivers.put(layouttype4.LAYOUT4_FLEX_FILES,
              new FlexFileLayoutDriver(4, 1,
                    flex_files_prot.FF_FLAGS_NO_IO_THRU_MDS,
                    ByteUnit.MiB.toBytes(1),
                    new utf8str_mixed("17"), new utf8str_mixed("17"), this::logLayoutErrors));

        _rpcService = oncRpcSvcBuilder.build();
        _rpcService.start();

        // we don't expect high traffic querying rquota, so we can use same thread strategy and limited number of threads
        _rquotaService = new OncRpcSvcBuilder()
                .withPort(_rquotaPort)
                .withUDP()
                .withServiceName("rquota")
                .withAutoPublish()
                .withSubjectPropagation()
                .withSelectorThreadPoolSize(4)
                .withSameThreadIoStrategy()
                .withRpcService(new OncRpcProgram(100011, 2), quotaSvc)
                .build();

        _rquotaService.start();
    }

    public void destroy() throws IOException {
        _rquotaService.stop();
        _rpcService.stop();
        _callbackExecutor.shutdown();
        if (_nfs4 != null) {
            _nfs4.getStateHandler().shutdown();
            _proxyIoFactory.shutdown();
        }
    }

    /*
     * Handle reply from the pool that mover actually started.
     *
     * If the pools is not know yet, create a mapping between pool name
     * and NFSv4.1 device id. Finally, notify waiting request that we have got
     * the reply for LAYOUTGET
     */
    public void messageArrived(PoolPassiveIoFileMessage<?> message) {

        String poolName = message.getPoolName();
        long verifier = message.getVerifier();
        InetSocketAddress[] poolAddresses = message.socketAddresses();

        _log.debug("NFS mover ready: {}", poolName);

        PoolDS device = _poolDeviceMap.getOrCreateDS(poolName, verifier, poolAddresses);


        // REVISIT 11.0: remove drop legacy support. Old polls will send legacy stateid.
        // stateid4 stateid = message.challange();
        Object stateObject = message.challange();
        stateid4 stateid = LegacyUtils.toStateid(stateObject);
        NfsTransfer transfer = _transfers.get(stateid);

        /*
         * We got a notification for a transfer which was not
         * started by us.
         *
         * Door reboot.
         */
        if (transfer != null) {
            transfer.redirect(device);
        }
    }

    public void messageArrived(DoorTransferFinishedMessage transferFinishedMessage) {

        NFS4ProtocolInfo protocolInfo = (NFS4ProtocolInfo) transferFinishedMessage.getProtocolInfo();
        _log.debug("Mover {} done.", protocolInfo.stateId());
        org.dcache.chimera.nfs.v4.xdr.stateid4 legacyStateid = protocolInfo.stateId();

        /*
         * there are two cases when we can get DoorTransferFinishedMessage:
         *   - triggered by client with CLOSE
         *   - triggered by pool shutdown.
         */
        stateid4 openStateId = new stateid4(legacyStateid.other, legacyStateid.seqid.value);
        NfsTransfer transfer = _transfers.get(openStateId);
        if (transfer == null) {
            return;
        }

        transfer.finished(transferFinishedMessage);
        Serializable error = transferFinishedMessage.getErrorObject();
        transfer.notifyBilling(transferFinishedMessage.getReturnCode(),
              error == null ? "" : error.toString());

        // Ensure that we do not kill re-started transfer
        if (transfer.getId() == transferFinishedMessage.getId()) {
            if (transfer.isWrite()) {
                /*
                 * Inject poison to ensure that any other attempt to re-use
                 * the transfer to fail. The cleanup will happen with open-state
                 * disposal on close.
                 */
                transfer.enforceErrorIfRunning(POISON);
            } else {
                // it's ok to remove read mover as it's safe to re-create it again.
                _transfers.remove(openStateId);
            }
        }
    }

    public DoorValidateMoverMessage<?> messageArrived(DoorValidateMoverMessage<?> message) {

        // REVISIT 11.0: remove drop legacy support. Old polls will send legacy stateid.
        // stateid4 stateid = message.getChallenge();
        Object stateObject = message.getChallenge();
        stateid4 stateid = LegacyUtils.toStateid(stateObject);

        boolean isValid = false;
        try {
            NFS4Client nfsClient = _nfs4.getStateHandler().getClientIdByStateId(stateid);
            // will throw exception if state does not exists
            nfsClient.state(stateid);
            isValid = true;
        } catch (BadStateidException e) {
        } catch (ChimeraNFSException e) {
            _log.warn("Unexpected NFS exception: {}", e.getMessage());
        }
        message.setIsValid(isValid);
        return message;
    }

    public void messageArrived(PoolStatusChangedMessage message) {
        if (message.getPoolState() == PoolStatusChangedMessage.DOWN) {
            _log.info("Pool disabled: {}", message.getPoolName());
            recallLayouts(message.getPoolName());
        }
    }

    // NFSv41DeviceManager interface

    /*
    	The most important calls is LAYOUTGET, OPEN, CLOSE, LAYOUTRETURN
    	The READ, WRITE and  COMMIT goes to storage device.

    	We assume the following mapping between nfs and dcache:

    	     NFS     |  dCache
    	_____________|________________________________________
    	LAYOUTGET    : get pool, bind the answer to the client
    	OPEN         : send IO request to the pool
    	CLOSE        : sent end-of-IO to the pool, LAYOUTRECALL
    	LAYOUTRETURN : unbind pool from client

     */

    @Override
    public device_addr4 getDeviceInfo(CompoundContext context, GETDEVICEINFO4args args)
          throws ChimeraNFSException {

        layouttype4 layoutType = layouttype4.valueOf(args.gdia_layout_type);
        LayoutDriver layoutDriver = getLayoutDriver(layoutType);

        PoolDS ds = _poolDeviceMap.getByDeviceId(args.gdia_device_id);
        if (ds == null) {
            return null;
        }

        // limit addresses returned to client to the same 'type' as clients own address
        // NOTICE: according to rfc1918 we allow access to private networks from public ip address
        // Site must take care that private IP space is not visible to site external clients.
        InetAddress clientAddress = context.getRemoteSocketAddress().getAddress();
        InetSocketAddress[] usableAddresses = Stream.of(ds.getDeviceAddr())
              .filter(a -> !a.getAddress().isLoopbackAddress() || clientAddress.isLoopbackAddress())
              .filter(
                    a -> !a.getAddress().isLinkLocalAddress() || clientAddress.isLinkLocalAddress())
              // due to bug in linux kernel we need to filter out IPv6 addresses if client connected
              // with IPv4.
              // REVISIT: remove this workaround as soon as RHEL 7.5 is released.
              .filter(a -> clientAddress.getAddress().length >= a.getAddress().getAddress().length)
              .toArray(InetSocketAddress[]::new);

        return layoutDriver.getDeviceAddress(usableAddresses);
    }

    /**
     * ask pool manager for a file
     * <p>
     * On successful reply from pool manager corresponding O request will be sent to the pool to
     * start a NFS mover.
     *
     * @throws ChimeraNFSException in case of NFS friendly errors ( like ACCESS )
     * @throws IOException         in case of any other errors
     */
    @Override
    public Layout layoutGet(CompoundContext context, LAYOUTGET4args args)
          throws IOException {

        Inode nfsInode = context.currentInode();
        layouttype4 layoutType = layouttype4.valueOf(args.loga_layout_type);
        final stateid4 stateid = Stateids.getCurrentStateidIfNeeded(context, args.loga_stateid);

        LayoutDriver layoutDriver = getLayoutDriver(layoutType);

        final NFS4Client client;
        if (context.getMinorversion() == 0) {
            /* if we need to run proxy-io with NFSv4.0 */
            client = context.getStateHandler().getClientIdByStateId(stateid);
        } else {
            client = context.getSession().getClient();
        }

        CDC cdcContext = CDC.reset(getCellName(), getCellDomainName());
        try {

            FsInode inode = _chimeraVfs.inodeFromBytes(nfsInode.getFileId());
            PnfsId pnfsId = new PnfsId(inode.getId());

            deviceid4[] devices;

            var ioKey = new OpenFile(client.getId(), nfsInode);
            stateid4 layoutStateid = layoutStates.get(ioKey);

            final NFS4State openStateId = client.state(stateid).getOpenState();
            final NFS4State layoutState;

            // serialize all requests by the same stateid
            synchronized (openStateId) {

                if (inode.type() != FsInodeType.INODE || inode.getLevel() != 0) {
                    /*
                     * all non regular files ( AKA pnfs dot files ) provided by door itself.
                     */
                    throw new LayoutUnavailableException("special DOT file");
                }

                NfsTransfer transfer = layoutStateid != null ? _transfers.get(layoutStateid) : null;
                if (transfer == null) {
                    Transfer.initSession(false, false);
                    NDC.push(pnfsId.toString());
                    NDC.push(
                            context.getRpcCall().getTransport().getRemoteSocketAddress().toString());

                    // layout, or a transfer in dCache language, must have a unique stateid
                    layoutState = client.createLayoutState(openStateId.getStateOwner());
                    layoutStateid = layoutState.stateid();

                    transfer = args.loga_iomode == layoutiomode4.LAYOUTIOMODE4_RW ?

                            new WriteTransfer(_pnfsHandler, client, layoutType, layoutState, nfsInode,
                                    context.getRpcCall().getCredential().getSubject())
                            :
                            new ReadTransfer(_pnfsHandler, client, layoutType, layoutState, nfsInode,
                                    context.getRpcCall().getCredential().getSubject());

                    _transfers.put(layoutStateid, transfer);
                    layoutStates.put(ioKey, layoutStateid);

                    final InetSocketAddress remote = client.getRemoteAddress();

                    final NFS4ProtocolInfo protocolInfo = new NFS4ProtocolInfo(remote,
                            new org.dcache.chimera.nfs.v4.xdr.stateid4(layoutStateid), nfsInode.toNfsHandle()
                    );

                    transfer.setProtocolInfo(protocolInfo);
                    transfer.setCellAddress(getCellAddress());
                    transfer.setBillingStub(_billingStub);
                    transfer.setPoolStub(_poolStub);
                    transfer.setPoolManagerStub(_poolManagerStub);
                    transfer.setPnfsId(pnfsId);
                    transfer.setClientAddress(remote);
                    transfer.setIoQueue(_ioQueue);
                    transfer.setKafkaSender(_kafkaSender);

                } else {
                    // keep debug context in sync
                    transfer.restoreSession();
                    layoutState = transfer.getStateid();
                    NDC.push(pnfsId.toString());
                    NDC.push(
                            context.getRpcCall().getTransport().getRemoteSocketAddress().toString());
                }

                devices = transfer.getPoolDataServers(NFS_REQUEST_BLOCKING);
            }

            //  -1 is special value, which means entire file
            layout4 layout = new layout4();
            layout.lo_iomode = args.loga_iomode;
            layout.lo_offset = new offset4(0);
            layout.lo_length = new length4(nfs4_prot.NFS4_UINT64_MAX);
            layout.lo_content = layoutDriver.getLayoutContent(layoutStateid, NFSv4Defaults.NFS4_STRIPE_SIZE, new nfs_fh4(nfsInode.toNfsHandle()), devices);

            layoutState.bumpSeqid();
            if (args.loga_iomode == layoutiomode4.LAYOUTIOMODE4_RW) {
                // in case of WRITE, invalidate vfs cache on close
                layoutState.addDisposeListener(state -> _vfsCache.invalidateStatCache(nfsInode));
            }

            return new Layout(true, layoutStateid, new layout4[]{layout});

        } catch (FileNotFoundCacheException e) {
            /*
             * The file is removed before we was able to start a mover.
             * Invalidate state as client will not send CLOSE for a stale file
             * handle.
             *
             * NOTICE: according POSIX, the opened file must be still accessible
             * after remove as long as it not closed. We violate that requirement
             * in favor of dCache shared state simplicity.
             */
            client.releaseState(stateid);
            throw new StaleException("File is removed", e);
        } catch (CacheException | ChimeraFsException | TimeoutException | ExecutionException e) {
            throw asNfsException(e, LayoutTryLaterException.class);
        } catch (InterruptedException e) {
            throw new LayoutTryLaterException(e.getMessage(), e);
        } finally {
            cdcContext.close();
        }

    }

    @Override
    public List<deviceid4> getDeviceList(CompoundContext context, GETDEVICELIST4args args) {
        return _poolDeviceMap.getDevices()
              .stream()
              .map(PoolDS::getDeviceId)
              .collect(toList());
    }

    private void logLayoutErrors(CompoundContext context, ff_layoutreturn4 lr) {
        for (ff_ioerr4 ioerr : lr.fflr_ioerr_report) {
            for (device_error4 de : ioerr.ffie_errors) {
                reportLayoutError(context, de);
            }
        }
    }

    private void reportLayoutError(CompoundContext context, device_error4 de) {
        PoolDS ds = _poolDeviceMap.getByDeviceId(de.de_deviceid);
        String pool = ds == null ? "an unknown pool" : ("pool " + ds.getName());
        _log.error("Client {} reports error {} on {} for op {}", toAddrString(
                    context.getRemoteSocketAddress().getAddress()),
              nfsstat.toString(de.de_status), pool, nfs_opnum4.toString(de.de_opnum));

        // rise an alarm when client can't connect to the pool
        if (de.de_status == nfsstat.NFSERR_NXIO) {
            _log.error(
                  AlarmMarkerFactory.getMarker(PredefinedAlarm.CLIENT_CONNECTION_REJECTED,
                        pool),
                  "Client {} failed to connect to {}", toAddrString(
                        context.getRemoteSocketAddress().getAddress()), pool);
        }
    }


    /*
     * (non-Javadoc)
     *
     * @see org.dcache.chimera.nfsv4.NFSv41DeviceManager#releaseDevice(stateid4 stateid)
     */
    @Override
    public void layoutReturn(CompoundContext context, LAYOUTRETURN4args args) throws IOException {

        if (args.lora_layoutreturn.lr_returntype == layoutreturn_type4.LAYOUTRETURN4_FILE) {
            layouttype4 layoutType = layouttype4.valueOf(args.lora_layout_type);
            final stateid4 stateid = Stateids.getCurrentStateidIfNeeded(context,
                  args.lora_layoutreturn.lr_layout.lrf_stateid);

            final NFS4Client client;
            if (context.getMinorversion() > 0) {
                client = context.getSession().getClient();
            } else {
                // v4.0 client use proxy adapter, which calls layoutreturn
                client = context.getStateHandler().getClientIdByStateId(stateid);
            }

            _log.debug("Releasing layout by stateid: {}", stateid);
            getLayoutDriver(layoutType).acceptLayoutReturnData(context,
                  args.lora_layoutreturn.lr_layout.lrf_body);

            NfsTransfer transfer = _transfers.get(stateid);
            if (transfer != null) {
                transfer.shutdownMover();
            }
            // any further use of this layout-stateid must fail with NFS4ERR_BAD_STATEID
            client.releaseState(stateid);
        }
    }

    @Override
    public OptionalLong layoutCommit(CompoundContext context, LAYOUTCOMMIT4args args)
          throws IOException {

        final stateid4 stateid = Stateids.getCurrentStateidIfNeeded(context, args.loca_stateid);

        var transfer = _transfers.get(stateid);
        if (transfer == null) {
            throw new StaleStateidException("No layout for stateid: " + stateid);
        } else if (!transfer.isWrite()) {
            throw new BadLayoutException("Invalid open mode");
        }

        Inode nfsInode = context.currentInode();

        _log.debug("Committing layout by stateid: {}", stateid);

        if (args.loca_last_write_offset.no_newoffset) {
            long currentSize = _chimeraVfs.getattr(nfsInode).getSize();
            long newSize = args.loca_last_write_offset.no_offset.value + 1;
            if (newSize > currentSize) {
                Stat newStat = new Stat();
                newStat.setSize(newSize);
                _chimeraVfs.setattr(nfsInode, newStat);
                _vfsCache.invalidateStatCache(nfsInode);
                return OptionalLong.of(newSize);
            }
        }
        return OptionalLong.empty();
    }

    @Override
    public void layoutError(CompoundContext context, LAYOUTERROR4args args) throws IOException {
        var errors = args.lea_errors;
        Arrays.stream(errors)
              .forEach(e -> reportLayoutError(context, e));
    }

    @Override
    public void layoutStats(CompoundContext context, LAYOUTSTATS4args args) throws IOException {
        // NOP for now. Forced by interface
    }

    /*
     * (non-Javadoc)
     *
     * @see org.dcache.nfsv4.NFSv41DeviceManager#getLayoutTypes()
     */
    @Override
    public Set<layouttype4> getLayoutTypes() {
        return _supportedDrivers.keySet();
    }

    public void setBillingStub(CellStub stub) {
        _billingStub = stub;
    }

    /*
     * Cell specific
     */
    @Override
    public void getInfo(PrintWriter pw) {

        pw.println("NFS (" + _versions + ") door (MDS):");
        if (_nfs4 != null) {
            pw.printf("  IO queue                : %s\n", _ioQueue);
            pw.printf("  Supported Layout types  : %s\n", _supportedDrivers.keySet());
            pw.printf("  Number of NFSv4 clients : %d\n",
                  _nfs4.getStateHandler().getClients().size());
            pw.printf("  Total pools (DS) used   : %d\n", _poolDeviceMap.getDevices().size());
            pw.printf("  Active transfers        : %d\n", _transfers.values().size());
            pw.printf("  Known proxy adapters    : %d\n", _proxyIoFactory.getCount());
        }
    }

    @Override
    public void setCuratorFramework(CuratorFramework client) {
        _curator = client;
    }

    /**
     * Get from zookeeper a instance wide unique ID associated with this door. If id doesn't exist,
     * then a global counter is used to assign one and store for the next time.
     *
     * @param base       The root of the zookeeper tree to use.
     * @param identifier The system wide identifier for this service.
     * @param storeName  Path where service id is stored.
     * @return newly created or stored id for this door.
     * @throws Exception REVISIT: this logic can be used by other components as well.
     */
    private int getOrCreateId(String base, String identifier, String storeName) throws Exception {
        String doorNode = ZKPaths.makePath(base, identifier);

        int stateMgrId;
        InterProcessSemaphoreMutex lock = new InterProcessSemaphoreMutex(_curator, base);
        lock.acquire();
        try {
            String idNode = ZKPaths.makePath(doorNode, storeName);
            org.apache.zookeeper.data.Stat zkStat = _curator.checkExists().forPath(idNode);
            if (zkStat == null || zkStat.getDataLength() == 0) {

                // use parent's change version as desired counter
                org.apache.zookeeper.data.Stat parentStat = _curator.setData()
                      .forPath(base);
                stateMgrId = parentStat.getVersion();

                _curator.create()
                      .orSetData()
                      .creatingParentContainersIfNeeded()
                      .withMode(CreateMode.PERSISTENT)
                      .forPath(idNode,
                            Integer.toString(stateMgrId).getBytes(StandardCharsets.US_ASCII));
            } else {
                byte[] data = _curator.getData().forPath(idNode);
                stateMgrId = Integer.parseInt(new String(data, StandardCharsets.US_ASCII));
            }

        } finally {
            lock.release();
        }
        return stateMgrId;
    }

    @Command(name = "kill mover", hint = "Kill mover on the pool.")
    public class KillMoverCmd implements Callable<String> {

        @Argument(index = 0, metaVar = "pool", usage = "pool name")
        String pool;

        @Argument(index = 1, metaVar = "moverid", usage = "mover id")
        int mover;

        @Override
        public String call() {
            PoolMoverKillMessage message = new PoolMoverKillMessage(pool, mover,
                  "killed by door 'kill mover' command");
            message.setReplyRequired(false);
            _poolStub.notify(new CellPath(pool), message);
            return "Done.";
        }

    }

    @Command(name = "exports reload", hint = "Re-scan export file.")
    public class ExportsReloadCmd implements Callable<String> {

        @Override
        public String call() throws IOException {
            _exportFile.rescan();
            updateLbPaths();
            return "Done.";
        }
    }

    @Command(name = "exports ls", hint = "Dump nfs exports.")
    public class ExportsLsCmd implements Callable<String> {

        @Argument(required = false)
        String host;

        @Override
        public String call() throws IOException {
            Stream<FsExport> exports;
            if (host != null) {
                InetAddress address = InetAddress.getByName(host);
                exports = _exportFile.exports(address);
            } else {
                exports = _exportFile.exports();
            }
            return exports
                  .map(Object::toString)
                  .collect(Collectors.joining("\n"));
        }
    }

    @Command(name = "kill client", hint = "kill NFS client and it's sessions",
          description = "With this command, dCache responds to " +
                "this specific client as if it was " +
                "restarted. All other NFS clients are " +
                "unaffected." +
                "\n\n" +
                "One use of this command is for an admin " +
                "to allow dCache to recover from a client " +
                "that is itself trying to recover from a " +
                "(perceived) error in dCache response, but " +
                "is failing to do so. Such behaviour can " +
                "result in the client becoming stuck." +
                "\n\n" +
                "This command should not be necessary " +
                "under normal circumstances; please " +
                "contact dCache support if you make " +
                "continued use of it.")
    public class KillClientCmd implements Callable<String> {

        @Argument(required = false, metaVar = "clientid", valueSpec = "hexadecimal client id string")
        String clientidStr;

        @Override
        public String call() throws IOException, CommandException {
            if (_nfs4 == null) {
                return "NFS4 server not running.";
            }

            long clientid;
            try {
                // the first 8 bytes (16 chars) of session id is the client id in hex.
                clientid = Long.parseLong(clientidStr.substring(0, 16), 16);
            } catch (NumberFormatException e) {
                throw new CommandException(2, "hexadecimal client id string is expected", e);
            }

            NFS4Client client = _nfs4.getStateHandler().getClient(new clientid4(clientid));
            _nfs4.getStateHandler().removeClient(client);
            return "Done.";
        }
    }

    @Command(name = "show clients", hint = "show active NFSv4 clients",
          description = "Show NFSv4 clients and corresponding sessions.")
    public class ShowClientsCmd implements Callable<String> {

        @Argument(required = false, metaVar = "host", usage = "address/netmask|pattern")
        String host;

        @Override
        public String call() throws IOException {
            if (_nfs4 == null) {
                return "NFSv4 server not running.";
            }

            Predicate<InetAddress> clientMatcher =
                  host == null ? c -> true : InetAddressMatcher.forPattern(host);

            StringBuilder sb = new StringBuilder();
            _nfs4.getStateHandler().getClients()
                  .stream()
                  .filter(c -> clientMatcher.test(c.getRemoteAddress().getAddress()))
                  .forEach(c -> {
                      sb.append("    ")
                            .append(c.getRemoteAddress())
                            .append(":")
                            .append(new String(c.getOwnerId(), StandardCharsets.UTF_8))
                            .append(":")
                            .append("v4.")
                            .append(c.getMinorVersion())
                            .append("\n");
                      for (NFSv41Session session : c.sessions()) {
                          sb.append("        ")
                                .append(session.id())
                                .append(" max slot: ")
                                .append(session.getHighestSlot())
                                .append("/")
                                .append(session.getHighestUsedSlot())
                                .append("\n");
                      }
                  });
            return sb.toString();
        }
    }

    @Command(name = "show pools", hint = "show pools to pNFS device mapping",
          description = "Show pools as pNFS devices, including id mapping and "
                + "known IP addresses.")
    public class ShowPoolsCmd implements Callable<String> {

        @Argument(required = false, metaVar = "pool")
        String pool;

        @Override
        public String call() throws IOException {
            return _poolDeviceMap.getDevices()
                  .stream()
                  .filter(p -> pool == null || p.getName().equals(pool))
                  .map(Object::toString)
                  .collect(Collectors.joining("\n"));
        }
    }

    @Command(name = "show transfers", hint = "show active transfers",
          description = "Show active transfers excluding proxy-io.")
    public class ShowTransfersCmd implements Callable<String> {

        @Option(name = "pool", usage = "An optional pool for filtering."
              + "  Specifying an empty string selects only transfers where no"
              + " pool has been selected. Glob pattern matching is supported.")
        Glob pool;

        @Option(name = "client", usage = "An optional client for filtering. Glob pattern matching is supported.")
        Glob client;

        @Option(name = "pnfsid", usage = "An optional pNFS ID for filtering. Glob pattern matching is supported.")
        Glob pnfsid;

        @Override
        public String call() throws IOException {

            return _transfers.values()
                  .stream()
                  .filter(d -> pool == null || pool.matches(
                        d.getPool() == null ? "" : d.getPool().getName()))
                  .filter(d -> client == null || client.matches(d.getClient().toString()))
                  .filter(d -> pnfsid == null || pnfsid.matches(d.getPnfsId().toString()))
                  .map(Object::toString)
                  .collect(Collectors.joining("\n"));
        }
    }

    @Command(name = "show open files", hint = "show all opened files",
          description = "Show open files and corresponding NFS client IPs. Not every open"
                + " has a layout (mover).")
    public class ShowOpenFilesCmd implements Callable<String> {

        @Override
        public String call() throws IOException {

            StringBuilder sb = new StringBuilder("Open files:").append("\n\n");
            _nfs4.getStateHandler().getFileTracker()
                  .getOpenFiles()
                  .forEach((i, l) -> {
                      try {
                          FsInode inode = _chimeraVfs.inodeFromBytes(i.getFileId());
                          PnfsId pnfsId = new PnfsId(inode.getId());
                          FsPath p = _pnfsHandler.getPathByPnfsId(pnfsId);
                          sb.append(pnfsId).append(" (").append(p).append("):").append("\n");
                          l.forEach(c -> sb.append("  ")
                                .append(c.getId()).append(" (")
                                .append(
                                      InetAddresses.toUriString(c.getRemoteAddress().getAddress()))
                                .append(":").append(c.getRemoteAddress().getPort())
                                .append(")")
                                .append("\n\n"));
                      } catch (BadHandleException | CacheException | ChimeraFsException e) {
                          sb.append("Error: ").append(e);
                      }
                  });

            return sb.toString();
        }
    }

    @Command(name = "show delegations", hint = "show all open-delegations",
          description = "Show open delegations and corresponding NFS client IPs.")
    public class ShowOpenDelegationFilesCmd implements Callable<String> {

        @Override
        public String call() throws IOException {

            StringBuilder sb = new StringBuilder("Open delegations:").append("\n\n");
            _nfs4.getStateHandler().getFileTracker()
                  .getDelegations()
                  .forEach((i, l) -> {
                      try {
                          FsInode inode = _chimeraVfs.inodeFromBytes(i.getFileId());
                          PnfsId pnfsId = new PnfsId(inode.getId());
                          FsPath p = _pnfsHandler.getPathByPnfsId(pnfsId);
                          sb.append(pnfsId).append(" (").append(p).append("):").append("\n");
                          l.forEach(c -> sb.append("  ")
                                .append(c.getId()).append(" (")
                                .append(
                                      InetAddresses.toUriString(c.getRemoteAddress().getAddress()))
                                .append(":").append(c.getRemoteAddress().getPort())
                                .append(")")
                                .append("\n\n"));
                      } catch (BadHandleException | CacheException | ChimeraFsException e) {
                          sb.append("Error: ").append(e);
                      }
                  });

            return sb.toString();
        }
    }

    @Command(name = "show proxyio", hint = "show proxy-io transfers",
          description = "Show active proxy-io transfers.")
    public class ShowProxyIoTransfersCmd implements Callable<String> {

        @Override
        public String call() throws IOException {
            if (_nfs4 == null) {
                return "NFSv4 server not running.";
            }

            StringBuilder sb = new StringBuilder();
            _proxyIoFactory.forEach(p -> {
                NfsTransfer t = _transfers.get(p.getStateId());
                sb.append(t).append('\n');
            });
            return sb.toString();
        }
    }

    static class PoolDS {

        private final String _name;
        private final deviceid4 _deviceId;
        private final InetSocketAddress[] _socketAddress;
        private final long _verifier;

        public PoolDS(String name, deviceid4 deviceId, InetSocketAddress[] ip, long verifier) {
            _name = name;
            _deviceId = deviceId;
            _socketAddress = ip;
            _verifier = verifier;
        }

        public deviceid4 getDeviceId() {
            return _deviceId;
        }

        public InetSocketAddress[] getDeviceAddr() {
            return _socketAddress;
        }

        public long getVerifier() {
            return _verifier;
        }

        public String getName() {
            return _name;
        }

        @Override
        public String toString() {
            return String.format("%s: DS: %s, InetAddress: %s",
                  _name, _deviceId, Arrays.toString(_socketAddress));
        }
    }


    private class ReadTransfer extends NfsTransfer {

        public ReadTransfer(PnfsHandler pnfs, NFS4Client client, layouttype4 layouttype, NFS4State layoutState,
              Inode nfsInode, Subject ioSubject) throws ChimeraNFSException {
            super(pnfs, client, layouttype, layoutState, nfsInode, ioSubject);
        }

        @Override
        deviceid4[] selectDataServers(long timeout) throws
              InterruptedException, ExecutionException,
              TimeoutException, CacheException, ChimeraNFSException {

            if (isFirstAttempt()) {
                readNameSpaceEntry(false);
                FileAttributes attr = getFileAttributes();

                boolean expectedOnline = !attr.getLocations().isEmpty();
                if (!(expectedOnline || attr.getStorageInfo().isStored())) {
                    throw new NfsIoException("lost file " + getPnfsId());
                }

                if (expectedOnline) {
                    try {

                        /*
                         * On a read we are OK to by-pass selection if file is available on a pool
                         * from which client is allowed to read. The retry mechanism of
                         * selectPoolAndStartMoverAsync will take care that stage or p2p takes
                         * place in case of incorrect information.
                         */
                        var pool = _poolMonitor.getPoolSelector(attr, getProtocolInfo(), null,
                                    null)
                              .selectReadPool();
                        setPool(new Pool(pool.name(), pool.address(), pool.assumption()));
                        setFileAttributes(attr);
                        _log.debug("by-passed selecting {} for read", pool);
                    } catch (FileNotInCacheException | PermissionDeniedCacheException | CostException e) {
                        // fallback to regular selection
                        _log.info("selection by-pass failed: {}", e.getMessage());
                        expectedOnline = false;
                    }
                }

                _log.debug("looking a read pool for {}", getPnfsId());
                _redirectFuture = selectPoolAndStartMoverAsync(POOL_SELECTION_RETRY_POLICY);
                if (!expectedOnline) {
                    // no reason to block the client as p2p or restore from HSM is required.
                    throw new LayoutTryLaterException("File is offline or p2p is required.");
                }
            }

            _redirectFuture.get(NFS_REQUEST_BLOCKING, TimeUnit.MILLISECONDS);
            _log.debug("mover ready: pool={} moverid={}", getPool(), getMoverId());

            deviceid4 ds = waitForRedirect(NFS_REQUEST_BLOCKING).getDeviceId();
            return new deviceid4[]{ds};
        }

        @Override
        public synchronized boolean hasMover() {
            // for read request we don't re-send mover kill
            return !shutdownInProgress && super.hasMover();
        }
    }

    private class WriteTransfer extends NfsTransfer {

        public WriteTransfer(PnfsHandler pnfs, NFS4Client client, layouttype4 layoutType, NFS4State layoutState,
              Inode nfsInode, Subject ioSubject) throws ChimeraNFSException {
            super(pnfs, client, layoutType, layoutState, nfsInode, ioSubject);

            // write movers kept as long as the file is opened
            layoutState.addDisposeListener(state -> _transfers.remove(this.getStateid().stateid()));
            layoutState.addDisposeListener(state -> layoutStates.remove(new OpenFile(client.getId(), nfsInode) ));
        }

        @Override
        deviceid4[] selectDataServers(long timeout) throws
              InterruptedException, ExecutionException,
              TimeoutException, CacheException, ChimeraNFSException {

            if (isFirstAttempt()) {
                readNameSpaceEntry(true);
                FileAttributes attr = getFileAttributes();

                /*
                 * allow writes only into new files
                 */
                if (!attr.getStorageInfo().isCreatedOnly()) {
                    throw new PermException("Can't modify existing file");
                }

                // REVISIT: this have to go into Transfer class.
                if (getFileAttributes().isDefined(FileAttribute.LOCATIONS)
                      && !getFileAttributes().getLocations().isEmpty()) {

                    /*
                     * If we need to start a write-mover for a file which already has
                     * a location assigned to it, then we stick that location and by-pass
                     * any pool selection step.
                     */
                    Collection<String> locations = getFileAttributes().getLocations();
                    if (locations.size() > 1) {
                        /*
                         * Huh! We don't support mirroring (yet), thus there
                         * can't by multiple locations, unless some something
                         * went wrong!
                         */
                        throw new ServerFaultException(
                              "multiple locations for: " + getPnfsId() + " : " + locations);
                    }
                    String location = locations.iterator().next();
                    _log.debug("Using pre-existing WRITE pool {} for {}", location, getPnfsId());
                    // REVISIT: here we know that pool name and address are the same thing
                    setPool(new Pool(location, new CellAddressCore(location), Assumptions.none()));
                    _redirectFuture = startMoverAsync(MAX_REQUEST_LIFETIME);
                } else {
                    _log.debug("looking a write pool for {}", getPnfsId());
                    _redirectFuture = selectPoolAndStartMoverAsync(POOL_SELECTION_RETRY_POLICY);
                }
            }

            _redirectFuture.get(NFS_REQUEST_BLOCKING, TimeUnit.MILLISECONDS);
            _log.debug("mover ready: pool={} moverid={}", getPool(), getMoverId());

            deviceid4 ds = waitForRedirect(NFS_REQUEST_BLOCKING).getDeviceId();
            return new deviceid4[]{ds};
        }
    }

    abstract private class NfsTransfer extends RedirectedTransfer<PoolDS> {

        private final Inode _nfsInode;
        private final NFS4State _stateid;
        protected ListenableFuture<Void> _redirectFuture;
        protected AtomicReference<ChimeraNFSException> _errorHolder = new AtomicReference<>();
        private final NFS4Client _client;
        protected boolean shutdownInProgress;
        private layouttype4 layouttype;

        NfsTransfer(PnfsHandler pnfs, NFS4Client client, layouttype4 layoutType, NFS4State layoutState,
              Inode nfsInode, Subject ioSubject) {
            super(pnfs, Subjects.ROOT, Restrictions.none(), Subjects.fromUnixNumericSubject(ioSubject), null);

            _nfsInode = nfsInode;
            _stateid = layoutState;

            this.layouttype = layoutType;

            /*
             * As all our layouts marked 'return-on-close', stop mover when
             * layout-state disposed (which will trigger by open state disposal) on CLOSE.
             */
            _stateid.addDisposeListener(state -> {
                /*
                 * Cleanup transfer when state invalidated.
                 */
                var t = this;
                if (t.hasMover()) {
                    // FIXME: as long as we can identify client's capabilities the workaround apply to flex_files layouts
                    // to work correctly with proxy-io. As modern rhel clients (>= 7) use flex_files by default it's ok.
                    if (layoutType == layouttype4.LAYOUT4_FLEX_FILES && client.isLeaseValid() && client.getCB() != null) {
                        /*
                         * Due to race in the Linux kernel client, a server might see CLOSE before
                         * the last WRITE operation have been processed by a data server. Thus,
                         * recall the layout (enforce dirty page flushing) and return a NFS4ERR_DELAY.
                         *
                         * see: https://bugzilla.redhat.com/show_bug.cgi?id=1901524
                         */
                        _log.warn(
                              "Deploying work-around for buggy client {} issuing CLOSE before LAYOUT_RETURN for transfer {}@{} of {}",
                              t.getMoverId(), t.getPool(), t.getPnfsId(),
                              t.getClient().getRemoteAddress());
                        t.recallLayout(_callbackExecutor);
                        throw new DelayException("Close before layoutreturn");
                    } else {
                        _log.warn("Removing orphan mover: {}@{} for {} by {}",
                              t.getMoverId(), t.getPool(), t.getPnfsId(),
                              t.getClient().getRemoteAddress());
                        t.shutdownMover();
                    }
                } else {
                    // no associated mover, thus, we need to remove the transfer manually as cleanup will be never be called
                    _transfers.remove(_stateid.stateid());
                }
            });
            _client = client;
        }

        @Override
        public String toString() {

            ZoneId timeZone = ZoneId.systemDefault();

            String status = getStatus();
            if (status == null) {
                status = "idle";
            }

            return String.format(
                  "    %s : %s : %s %s@%s, OS=%s, cl=[%s], status=[%s], redirected=%b",
                  DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(getCreationTime()),
                              timeZone)),
                  getPnfsId(),
                  this.getClass().getSimpleName(),
                  getMoverId(),
                  Optional.ofNullable(getPool()).map(Pool::getName).orElse("N/A"),
                  ((NFS4ProtocolInfo) getProtocolInfoForPool()).stateId(),
                  ((NFS4ProtocolInfo) getProtocolInfoForPool()).getSocketAddress().getAddress()
                        .getHostAddress(),
                  status,
                  getRedirect() != null);
        }

        Inode getInode() {
            return _nfsInode;
        }

        /**
         * Returns an array of pNFS devices to be used for this transfer. If array have more than
         * one element, then mirror IO is desired.
         *
         * @param timeout time in milliseconds to block before error is returned.
         * @return an array of pNFS devices.
         * @throws InterruptedException
         * @throws ExecutionException
         * @throws TimeoutException
         * @throws CacheException
         * @throws ChimeraNFSException
         */
        @GuardedBy("nfsState")
        deviceid4[] getPoolDataServers(long timeout) throws
              InterruptedException, ExecutionException,
              TimeoutException, CacheException, ChimeraNFSException {

            ChimeraNFSException error = _errorHolder.get();
            if (error != null) {
                throw error;
            }

            if (shutdownInProgress) {
                // race with competing open. Let wait for pending mover shutdown to complete.
                _log.info("Waiting for pending mover shutdown to complete: {}@{} ", this.getMoverId(), this.getPool());
                throw new DelayException("Waiting for pending mover shutdown to complete");
            }

            /*
             * If already have triggered selection process, then there are no
             * reasons to block. The async reply will update _redirectFuture when
             * it's timed out or ready.
             */
            if (!isFirstAttempt() && !_redirectFuture.isDone()) {
                throw new LayoutTryLaterException("Waiting for pool to become ready.");
            }

            try {
                return selectDataServers(timeout);
            } catch (InterruptedException | ExecutionException
                  | TimeoutException | CacheException | ChimeraNFSException e) {

                // failed without any pool has been selected.
                // Depending on the error client may re-try the requests.
                if (_redirectFuture != null && _redirectFuture.isDone() && getPool() == null) {
                    _redirectFuture = null;
                }

                throw e;
            }
        }

        /**
         * Indicates whatever this is a first attempt to get a data servers or a retry.
         *
         * @return true is this is a first attempt to get a data servers.
         */
        protected boolean isFirstAttempt() {
            return _redirectFuture == null;
        }

        abstract deviceid4[] selectDataServers(long timeout) throws
              InterruptedException, ExecutionException,
              TimeoutException, CacheException, ChimeraNFSException;

        /**
         * Retry transfer.
         */
        private String retry() {

            /*
             * client re-try will trigger transfer
             */
            if (_redirectFuture == null) {
                return "Nothing to do.";
            }

            /*
             * The transfer is in the middle of an action
             */
            String s = getStatus();
            if (s != null) {
                return "Can't reset transfer in action: " + s;
            }

            /*
             * Reply from pool selection is lost. It's safe to start over.
             */
            if (getPool() == null) {
                _redirectFuture = null;
                return "Restarting from pool selection";
            }

            /*
             * Mover id is lost. it's ok to start it again, as pool will start
             * mover for given transfer only once.
             */
            if (!hasMover()) {
                _redirectFuture = startMoverAsync(NFS_REQUEST_BLOCKING);
                return "Re-activating mover on: " + getPool();
            }

            /*
             * Redirect is complete.
             */
            if (getRedirect() != null) {
                return "Can't re-try complete mover.";
            }

            /**
             * Redirect is lost
             */
            // currently there are no possibilities to force too to re-send redirect.

            return "Lost redirect...";
        }

        public synchronized void shutdownMover() throws NfsIoException, DelayException {

            CDC cdcContext = CDC.reset(getCellName(), getCellDomainName());
            restoreSession();
            try {

                if (!hasMover()) {
                    // the mover clean-up will not be called, thus we have to clean manually
                    _transfers.remove(_stateid.stateid());
                    return;
                }

                _log.debug("Shutting down transfer: {}", this);
                killMover(0, "killed by door: returning layout");
                shutdownInProgress = true;

                // wait for clean mover shutdown only for writes only
                if (isWrite() && !waitForMover(NFS_REQUEST_BLOCKING)) {
                    throw new DelayException("Mover not stopped");
                }
            } catch (FileNotFoundCacheException e) {
                // REVISIT: remove when pool will stop sending this exception
                _log.info("File removed while being opened: {}@{} : {}",
                      getMoverId(), getPool(), e.getMessage());
            } catch (CacheException | InterruptedException e) {
                _log.info("Failed to kill mover: {}@{} : {}",
                      getMoverId(), getPool(), e.getMessage());
                throw new NfsIoException(e.getMessage(), e);
            } finally {
                cdcContext.close();
            }
        }

        /**
         * Set a {@link ChimeraNFSException} which will be thrown when {@link
         * #getPoolDataServers(long)} is called. An existing object will not be replaced. This
         * method is used to enforce error condition on an attempt to select a pool.
         *
         * @param e exception to be thrown.
         * @return {@code true} if new exception is set.
         */
        boolean enforceErrorIfRunning(ChimeraNFSException e) {
            return _errorHolder.compareAndSet(null, e);
        }

        NFS4State getStateid() {
            return _stateid;
        }

        NFS4Client getClient() {
            return _client;
        }

        /**
         * Recall file layout from the client. As re-calling is an async action. call executed in
         * dedicated {@code executorService}. If client can't return layout right away, then recall
         * will we re-scheduled.
         *
         * @param executorService executor service to use.
         */
        void recallLayout(ScheduledExecutorService executorService) {

            ChimeraNFSException e = isWrite() ? POISON : DELAY;
            if (!enforceErrorIfRunning(e)) {
                // already recalled
                return;
            }

            /**
             * pool selection complete, but no mover or redirect. Forget transfer.
             */
            if (getMoverId() == null || getRedirect() == null) {
                _log.info("Forgetting transfer {}", this);
                cleanup();
                return;
            }

            if (_client.getCB() == null) {
                _log.info("Can't recall layout - no callback channel");
                return;
            }

            // bump sequence as we do a new action on layout
            _stateid.bumpSeqid();

            _log.info("Recalling layout from {}", _client);
            executorService.submit(
                  new FireAndForgetTask(new LayoutRecallTask(this, executorService)));
        }

        /**
         * clean all internal states to simulate a new transfer
         */
        private void cleanup() {

            // writes should stay poisoned to fail on client retry.
            if (!isWrite()) {
                _transfers.remove(_stateid.stateid());
            }
            killMover(0, "layout recall on pool down");
            // keep NFSTransfer#shutdown happy
            finished((CacheException) null);
        }
        /**
         * Remove transfer associated with a dead client.
         */
        private void cleanupNoclient() {
            _transfers.remove(_stateid.stateid());
            killMover(0, "layout recall on dead client");
            // keep NFSTransfer#shutdown happy
            finished((CacheException) null);
        }

        @Override
        public String getTransferPath() {
            // no difference for nfs
            return this.getBillingPath();
        }

        @Override
        public synchronized String getBillingPath() {
            /*
             * NFS door doesn't know the path and expects that namespace will populate it as a part of storage info.
             */
            if (getFileAttributes().isDefined(FileAttribute.STORAGEINFO)) {
                return getFileAttributes().getStorageInfo().getKey("path");
            } else {
                return "N/A";
            }
        }

        /**
         * Get pNFS layouttype associated with this transfer.
         * @return
         */
        public layouttype4 getLayouttype() {
            return layouttype;
        }
    }

    /*
     * To allow the transfer monitoring in the httpd cell to recognize us
     * as a door, we have to emulate LoginManager.  To emulate
     * LoginManager we list ourselves as our child.
     */
    @Command(name = "get children", hint = "Get door's children associated with transfers.")
    public class GetDoorChildrenCmd implements Callable<Serializable> {

        @Option(name = "binary", usage = "returns binary object instead of string form")
        boolean isBinary;

        @Override
        public Serializable call() {
            if (isBinary) {
                String[] children = new String[]{NFSv41Door.this.getCellName()};
                return new LoginManagerChildrenInfo(NFSv41Door.this.getCellName(),
                      NFSv41Door.this.getCellDomainName(), children);
            } else {
                return NFSv41Door.this.getCellName();
            }
        }
    }

    @Command(name = "get door info", hint = "Provide information about the door and current transfers.")
    public class GetDoorInfoCmd implements Callable<Serializable> {

        @Option(name = "binary", usage = "returns binary object instead of string form")
        boolean isBinary;

        @Override
        public Serializable call() {

            List<IoDoorEntry> entries = _transfers.values()
                  .stream()
                  .map(Transfer::getIoDoorEntry)
                  .collect(toList());

            IoDoorInfo doorInfo = new IoDoorInfo(NFSv41Door.this.getCellName(),
                  NFSv41Door.this.getCellDomainName());
            doorInfo.setProtocol("NFSV4.1", "0");
            doorInfo.setOwner("");
            doorInfo.setProcess("");
            doorInfo.setIoDoorEntries(entries.toArray(IoDoorEntry[]::new));
            return isBinary ? doorInfo : doorInfo.toString();
        }
    }

    @Command(name = "stats", hint = "Show nfs requests statistics.")
    public class NfsStatsCmd implements Callable<String> {

        @Option(name = "c", usage = "clear current statistics values")
        boolean clean;

        @Override
        public String call() {
            RequestExecutionTimeGauges<String> gauges = _executor.getStatistics();
            StringBuilder sb = new StringBuilder();
            sb.append("Stats:").append("\n").append(gauges.toString("ns"));

            if (clean) {
                gauges.reset();
            }
            return sb.toString();
        }
    }

    @Command(name = "pool reset id", hint = "Reset device id associated with the pool.")
    public class PoolResetDeviceidCmd implements Callable<String> {

        @Argument(metaVar = "pool")
        String pool;

        @Option(name = "recall", usage = "recall layouts pointing to this device.")
        boolean recall;

        @Override
        public String call() throws CommandException {

            PoolDS ds = _poolDeviceMap.remove(pool);
            checkCommand(ds != null, "Pool %s not found", pool);

            deviceid4 dev = ds.getDeviceId();

            // FIXME: we should wait for all in use layouts being released
            if (recall) {
                _transfers.values().stream()
                      .filter(t -> t.getRedirect() != null)
                      .filter(t -> pool.equals(t.getPool().getName()))
                      .forEach(t -> t.recallLayout(_callbackExecutor));
            }

            _nfs4.getStateHandler().getClients().forEach(c -> {
                try {
                    ClientCB bc = c.getCB();
                    if (bc != null) {
                        c.getCB().cbDeleteDevice(dev);
                    }
                } catch (IOException e) {
                    _log.error("Failed to issue remove device callback: {}", e.toString());
                }
            });
            return "Pool " + pool + " as " + dev + " removed.";
        }
    }

    @Command(name = "recall layout", hint = "recall all layouts for given pool.")
    public class PoolDisableCmd implements Callable<String> {

        @Argument(metaVar = "pool")
        String pool;

        @Override
        public String call() {
            long n = recallLayouts(pool);
            return n + " layouts scheduled for recall.";
        }
    }

    @Command(name = "transfer retry", hint = "retry transfer for given open state.",
          description = "Retry pool selection or mover creation for a given transfer. "
                + "this can be necessary if components involved in selection "
                + "process were restarted before a reply was delivered to the door.")
    public class TransferRetryCmd implements Callable<String> {

        @Argument(metaVar = "stateid")
        String os;

        @Override
        public String call() {
            stateid4 stateid = new stateid4(BaseEncoding.base16().lowerCase().decode(os), 0);
            NfsTransfer t = _transfers.get(stateid);
            if (t == null) {
                return "No matching transfer";
            }
            return t.retry();
        }
    }

    @Command(name = "transfer forget", hint = "remove transfer for a given open state.",
          description = "Remove transfer from the list of active transfers. If client retry the"
                + "request, then a new transfer will be created.")
    public class TransferForgetCmd implements Callable<String> {

        @Argument(metaVar = "stateid", usage = "nfs open state id associated with the transfer.")
        String os;

        @Option(name = "kill-mover", usage = "try to kill mover, if exists.")
        boolean killMover;

        @Override
        public String call() {
            stateid4 stateid = new stateid4(BaseEncoding.base16().lowerCase().decode(os), 0);
            NfsTransfer t = _transfers.remove(stateid);
            if (t == null) {
                return "No matching transfer";
            }
            if (killMover) {
                t.killMover(0, TimeUnit.SECONDS, "manual transfer termination");
            }
            return "Removed: " + t;
        }
    }

    /**
     * Handle pool disable.
     *
     * @param pool name of the disabled pool.
     * @return number of affected transfers.
     */
    private synchronized long recallLayouts(String pool) {

        return _transfers.values().stream()
              .filter(t -> t.getPool() != null)
              .filter(t -> pool.equals(t.getPool().getName()))
              .filter(t -> t.getClient().getMinorVersion() > 0)
              .peek(t -> t.recallLayout(_callbackExecutor))
              .count();
    }

    private void updateLbPaths() {
        List<String> exportPaths = _exportFile.exports().map(FsExport::getPath).distinct()
              .collect(toList());
        _loginBrokerPublisher.setReadPaths(exportPaths);
        _loginBrokerPublisher.setWritePaths(exportPaths);
    }

    private LayoutDriver getLayoutDriver(layouttype4 layoutType)
          throws BadLayoutException, UnknownLayoutTypeException {

        LayoutDriver layoutDriver = _supportedDrivers.get(layoutType);
        if (layoutDriver == null) {
            throw new UnknownLayoutTypeException("Layout type (" + layoutType + ") not supported");
        }
        return layoutDriver;
    }

    private static class LayoutRecallTask implements Runnable {

        private final NfsTransfer transfer;
        private final ScheduledExecutorService executorService;

        public LayoutRecallTask(NfsTransfer transfer, ScheduledExecutorService executorService) {
            this.transfer = transfer;
            this.executorService = executorService;
        }

        @Override
        public void run() {
            try {
                transfer.getClient().getCB()
                      .cbLayoutRecallFile(new nfs_fh4(transfer.getInode().toNfsHandle()),
                            transfer.getStateid().stateid(), transfer.getLayouttype());
            } catch (NoMatchingLayoutException e) {
                /**
                 * In case of "forgetful client model", nfs client will return
                 * NFS4ERR_NOMATCHING_LAYOUT for existing layout. We can simply
                 * kill the transfer, as client will create a new one.
                 */
                _log.debug("forgetful client model");
                transfer.killMover(0, "layout recall");
            } catch (DelayException e) {
                // probably we hit in the middle of IO, try again
                _log.debug("Client can't return layout: re-scheduling layout recall");
                executorService.schedule(this, 2, TimeUnit.SECONDS);
            } catch (IOException e) {
                _log.error("Failed to send call-back to the client: {}", Exceptions.messageOrClassName(e));
                transfer.cleanupNoclient();
            }
        }
    }
}
