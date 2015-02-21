package net.thechunk.playpen.plugin.slack;

import com.ullink.slack.simpleslackapi.*;
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
public class SlackPlugin extends AbstractPlugin implements INetworkListener, SlackMessageListener {
    private SlackSession session = null;
    private SlackChannel channel = null;
    private SlackUser user = null;

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
        session.addMessageListener(this);
        session.connect();

        channel = session.findChannelByName(getConfig().getString("channel"));
        user = session.findUserByUserName("playpen");

        if(channel == null) {
            log.fatal("Unable to find channel " + getConfig().getString("channel"));
            return false;
        }

        if(user == null) {
            log.fatal("Unable to find user playpen");
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

    @Override
    public void onSessionLoad(SlackSession session) {
        // don't care
    }

    @Override
    public void onMessage(SlackMessage message) {
        if(!message.getChannel().getId().equals(channel.getId()))
            return; // we only want our channel

        if(message.getSender().getId().equals(user.getId()))
            return; // ignore

        String myUser = ("<@" + user.getId() + ">").toLowerCase();

        if(message.getMessageContent().toLowerCase().startsWith(myUser)) {
            String[] args = message.getMessageContent().split(" ");
            if(args.length < 2) {
                sendMessage("Hi there! Say '@playpen help' for a list of commands.");
            }

            switch(args[1]) {
                default:
                    sendMessage("Unknown command '" + args[1] + "', try saying '@playpen help'!");
                    break;

                case "help":
                    sendMessage("Available commands:\n" +
                            "help, list");
                    break;

                case "list":
                    runListCommand(args);
                    break;
            }
        }
    }

    private void runListCommand(String[] args) {
        if(args.length != 2) {
            sendMessage("Usage: @playpen list\n" +
                    "Displays a list of all active coordinators and servers.");
            return;
        }

        sendMessage("Give me a moment...");

        int count = 0;

        String result = "";
        for(LocalCoordinator coord : Network.get().getCoordinators().values()) {
            if(!coord.isEnabled() || coord.getChannel() == null || !coord.getChannel().isActive())
                continue;

            count++;

            result += "Coordinator " + coord.getName() + '\n';
            result += "  uuid: " + coord.getUuid() + '\n';

            for(Server server : coord.getServers().values()) {
                if(!server.isActive())
                    continue;

                result += "  Server " + server.getName() + '\n';
                result += "    uuid: " + server.getUuid() + '\n';
                result += "    package: " + server.getP3().getId() + " (" + server.getP3().getVersion() + ")\n";
            }
        }

        if(count == 0) {
            sendMessage("There are no active coordinators for me to list!");
            return;
        }

        sendMessage(result);
    }
}
