package net.thechunk.playpen.plugin.slack;

import com.ullink.slack.simpleslackapi.SlackChannel;
import com.ullink.slack.simpleslackapi.SlackSession;
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory;
import lombok.extern.log4j.Log4j2;
import net.thechunk.playpen.coordinator.CoordinatorMode;
import net.thechunk.playpen.coordinator.PlayPen;
import net.thechunk.playpen.coordinator.network.INetworkListener;
import net.thechunk.playpen.coordinator.network.LocalCoordinator;
import net.thechunk.playpen.coordinator.network.Network;
import net.thechunk.playpen.coordinator.network.Server;
import net.thechunk.playpen.plugin.AbstractPlugin;
import net.thechunk.playpen.plugin.EventManager;

@Log4j2
public class SlackPlugin extends AbstractPlugin implements INetworkListener {
    private SlackSession session = null;
    private SlackChannel channel = null;

    public void sendMessage(String message) {
        session.sendMessage(channel, message, null, "playpen", null);
    }

    @Override
    public boolean onStart() {
        if(PlayPen.get().getCoordinatorMode() != CoordinatorMode.NETWORK) {
            log.fatal("Slack plugin may only be used on a network coordinator");
            return false;
        }

        session = SlackSessionFactory.createWebSocketSlackSession(getConfig().getString("api-key"));
        session.connect();

        channel = session.findChannelByName(getConfig().getString("channel"));

        if(channel == null) {
            log.fatal("Unable to find channel " + getConfig().getString("channel"));
            return false;
        }

        return Network.get().getEventManager().registerListener(this);
    }

    @Override
    public void onNetworkStartup() {
        sendMessage("Network coordinator has started");
    }

    @Override
    public void onNetworkShutdown() {
        sendMessage("Network coordinator has shut down");
    }

    @Override
    public void onCoordinatorCreated(LocalCoordinator localCoordinator) {
        // don't care
    }

    @Override
    public void onCoordinatorSync(LocalCoordinator localCoordinator) {
        // don't care
    }

    @Override
    public void onRequestProvision(LocalCoordinator localCoordinator, Server server) {
        sendMessage("Provisioning " + server.getP3().getId() + " (" + server.getP3().getVersion() + ") on " +
                "coordinator " + localCoordinator.getName() + " as server " + server.getName());
    }

    @Override
    public void onProvisionResponse(LocalCoordinator localCoordinator, Server server, boolean b) {
        if(b) {
            sendMessage(server.getName() + " has been provisioned");
        }
        else {
            sendMessage(server.getName() + " failed provisioning");
        }
    }

    @Override
    public void onRequestDeprovision(LocalCoordinator localCoordinator, Server server) {
        sendMessage("Deprovisioning server " + server.getName());
    }

    @Override
    public void onServerShutdown(LocalCoordinator localCoordinator, Server server) {
        sendMessage("Server " + server.getName() + " has shut down");
    }

    @Override
    public void onRequestShutdown(LocalCoordinator localCoordinator) {
        sendMessage("Shutting down coordinator " + localCoordinator.getName());
    }

    @Override
    public void onListenerRegistered(EventManager<INetworkListener> eventManager) {
        // don't care
    }

    @Override
    public void onListenerRemoved(EventManager<INetworkListener> eventManager) {
        // don't care
    }
}