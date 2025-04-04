package puregero.multipaper.server.velocity.serverselection.strategy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import puregero.multipaper.server.ServerConnection;

import java.util.Collection;

public class WeightedTickTime implements ServerSelectionStrategy {

    @Override
    public RegisteredServer selectServer(Collection<RegisteredServer> servers, Player player) {
        
        RegisteredServer bestServer = null;
        long bestQuality = Long.MAX_VALUE;

        for (RegisteredServer server : servers) {
            String serverName = server.getServerInfo().getName();
            long players = server.getPlayersConnected().size();
            ServerConnection connection = ServerConnection.getConnection(serverName);

            if (connection != null && ServerConnection.isAlive(serverName)
                    && (connection.getTimer().averageInMillis() * 5 + players < bestQuality)) {
                // bestServer calculation formula
                bestQuality = connection.getTimer().averageInMillis() * 5 + players;
                bestServer = server;
            }
        }

        return bestServer;

    }
}
