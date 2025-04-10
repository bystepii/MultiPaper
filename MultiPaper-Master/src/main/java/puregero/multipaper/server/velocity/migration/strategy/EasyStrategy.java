package puregero.multipaper.server.velocity.migration.strategy;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.velocitypowered.api.proxy.server.RegisteredServer;

import puregero.multipaper.server.ServerConnection;
import puregero.multipaper.server.velocity.BaseStrategy;
import puregero.multipaper.server.velocity.MultiPaperVelocity;

class ServerWithData {

    protected Boolean perf;
    protected RegisteredServer server;
    protected int players;

    protected ServerWithData(Boolean perfDeg, RegisteredServer server, int players){
        this.perf = perfDeg;
        this.server = server;
        this.players = players;

    }

}

public class EasyStrategy extends BaseStrategy {

    private static final int DEFAULT_MSPT_HIGH = 40;
    private static final int DEFAULT_MSPT_LOW = 10;
    private static final double DEFAULT_RED_RATIO = 0.6;
    private static final double DEFAULT_SCALEUP_RATIO = 0.3;
    private static final double DEFAULT_SCALEDOWN_RATIO = 0.2;
    private static final long DEFAULT_INTERVAL = 60;
    private static final String DEFAULT_UNIT_TIME = "SECONDS";
    private static final double DEFAULT_PLAYERS_TRANSFER = 0.2;
    private static final int DEFAULT_MIN_SERVERS_MIG = 5;
    private static final int DEFAULT_MIN_SERVERS_DOWN = 2;
    
    private int msptHigh;
    private int msptLow;
    private double scaleUpRatio;
    private double scaleDownRatio;
    private double red;
    private double playersT;

    public EasyStrategy(Long interval, TimeUnit timeUnit) {
        super(Long.MAX_VALUE, TimeUnit.DAYS);
    }

    @Override
    public void onStartup(MultiPaperVelocity plugin) {
        super.onStartup(plugin);
        this.msptHigh = Math.toIntExact(config.getLong("performance.tick_length.high", (long) DEFAULT_MSPT_HIGH));
        this.msptLow  = Math.toIntExact(config.getLong("performance.tick_length.low", (long) DEFAULT_MSPT_LOW));
        this.scaleUpRatio = config.getDouble("performance.scaleUpRatio", DEFAULT_SCALEUP_RATIO);
        this.scaleDownRatio = config.getDouble("performance.scaleDownRatio", DEFAULT_SCALEDOWN_RATIO);
        this.red      = config.getDouble("migration.redL", DEFAULT_RED_RATIO);
        this.playersT = config.getDouble("migration.playersT", DEFAULT_PLAYERS_TRANSFER);
    
        this.interval = config.getLong("migration.interval", DEFAULT_INTERVAL);
        this.timeUnit = TimeUnit.valueOf(config.getString("migration.units", DEFAULT_UNIT_TIME));

        plugin.getProxy().getScheduler().buildTask(plugin, this::executeStrategy)
            .repeat(interval, timeUnit)
            .schedule();
    }

    @Override
    public void executeStrategy() {

        long redServers;
        long scaleUpServers;
        long scaleDownServers;

        Collection<RegisteredServer> allServers = plugin
                .getProxy()
                .getAllServers();

        // at startup time there are no registered servers...
        if (allServers.size() == 0) {
            logger.info("Waiting for servers before starting migration strategy !");
            return;
        }

        Collection<ServerWithData> serversWD = allServers
            .stream()
            .map(server -> new ServerWithData(
                ServerConnection.getConnection(server.getServerInfo().getName()).getTimer().averageInMillis() >= msptHigh,
                server,
                server.getPlayersConnected().size()))
            .collect(Collectors.toList());

        Map<Boolean, List<ServerWithData>> partitionedServers = serversWD
            .stream()
            .collect(Collectors.partitioningBy(server -> server.perf));

        ServerWithData[] serversBad = partitionedServers.get(true).toArray(new ServerWithData[0]);
        ServerWithData[] serversOk  = partitionedServers.get(false).toArray(new ServerWithData[0]);

        long counterBad = serversBad.length;
        long counterOk  = serversOk.length;

        if (counterBad == 0) {
            // To consider to scale down servers

            // don't scale down if there is only one server
            if(allServers.size() <= 1) {
                logger.info("EasyStrategy: There are no servers to scale down !");
                return;
            }

            // if all servers are below the threshold, scale down
            long counterDown = allServers
                                .stream()
                                .filter(server -> ServerConnection
                                    .getConnection(server.getServerInfo().getName())
                                    .getTimer()
                                    .averageInMillis() < msptLow)
                                .count();

            scaleDownServers  = (long) Math.round(scaleDownRatio * (double) counterDown);

            // delete servers with the lower amount of players, when all servers are ok and if there are more than 2
            if (counterDown == allServers.size() && allServers.size() > DEFAULT_MIN_SERVERS_DOWN) {
                logger.info("EasyStrategy: Scale down possible, deleting {} servers!!", scaleDownServers);
            //    for (int i = 0; i < scaleDownServers; i++) {
            //     allServers
            //             .stream()
            //             .min(Comparator.comparingInt(s -> s.getPlayersConnected().size()))
            //             .ifPresent(server -> {
            //                 plugin.getScalingManager().deletePod(server.getServerInfo().getName());
            //             });
            //    }
            } else {
                logger.info("EasyStrategy: Nothing to do !");
            }

            return;
        }

        // Now to consider migration of players or scale up servers

        logger.info("EasyStrategy: servers with degraded tick time: {}", counterBad);

        redServers      = (long) Math.round(red * (double) counterBad);
        scaleUpServers  = (long) Math.round(scaleUpRatio * (double) counterBad);
        
        // From here, we have servers with degraded tick time
        // if there is a low nº of servers, ex: 2 servers --> yellow = red = 1
        // we can set a rule: if nº of servers < 5 only scale up is an option
        if (allServers.size() <= DEFAULT_MIN_SERVERS_MIG) {
            logger.info("EasyStrategy: Scale up needed, adding {} servers!!", scaleUpServers);
            //plugin.getScalingManager().scaleUp();
            return;
        }

        // From here, we have servers with degraded tick time and enough servers to migrate players
        if (counterBad < redServers) {
            logger.info("EasyStrategy: Required players transfer !!");
            
            // how many players to transfer ??
            long playersToTransfer = 0;
            for (ServerWithData serverWD : serversBad){
                playersToTransfer = (long) Math.round(playersT * (double) serverWD.players);           
            
                // to which server ? to the one which is Ok with less players
                ServerWithData candidate = Arrays.stream(serversOk).min(Comparator.comparingInt(serverX -> serverX.players)).orElse(null);

                if (candidate != null) {
                    serverWD.server.getPlayersConnected().stream()
                        .limit(playersToTransfer)
                        .forEach(player -> plugin.transferPlayer(player, candidate.server, 5));
                    logger.info("EasyStrategy: Transferring {} players to another server !!", playersToTransfer); 
                } else {
                    logger.info("EasyStrategy: Not possible to transfer players to {} !!", candidate.server);
                }
            }
        } 
        else {
            logger.info("EasyStrategy: Scale up needed, adding {} servers!!", scaleUpServers);
            //plugin.getScalingManager().scaleUp();
        }
       
    }

}
