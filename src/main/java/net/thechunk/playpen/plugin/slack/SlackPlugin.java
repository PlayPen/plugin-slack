package net.thechunk.playpen.plugin.slack;

import com.ullink.slack.simpleslackapi.*;
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory;
import lombok.extern.log4j.Log4j2;
import net.thechunk.playpen.coordinator.CoordinatorMode;
import net.thechunk.playpen.coordinator.PlayPen;
import net.thechunk.playpen.coordinator.network.*;
import net.thechunk.playpen.p3.P3Package;
import net.thechunk.playpen.plugin.AbstractPlugin;
import net.thechunk.playpen.plugin.EventManager;
import net.thechunk.playpen.plugin.IPlugin;

import java.util.*;
import java.util.regex.Pattern;

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

            switch(args[1].toLowerCase()) {
                default:
                    sendMessage("Unknown command '" + args[1] + "', try saying '@playpen help'!");
                    break;

                case "help":
                    sendMessage("Available commands:\n" +
                            "help, list, provision, deprovision, shutdown, promote, generate-keypair, send, freeze, " +
                            "list-packages, list-plugins");
                    break;

                case "list":
                    runListCommand(args);
                    break;

                case "provision":
                    runProvisionCommand(args);
                    break;

                case "deprovision":
                    runDeprovisionCommand(args);
                    break;

                case "shutdown":
                    runShutdownCommand(args);
                    break;

                case "promote":
                    runPromoteCommand(args);
                    break;

                case "generate-keypair":
                    runGenerateKeypairCommand(args);
                    break;

                case "send":
                    runSendCommand(args);
                    break;

                case "freeze":
                    runFreezeCommand(args);
                    break;

                case "list-packages":
                    runListPackagesCommand(args);
                    break;

                case "list-plugins":
                    runListPluginsCommand(args);
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

                for(Map.Entry<String, String> entry : server.getProperties().entrySet()) {
                    result += "    prop: " + entry.getKey() + " = " + entry.getValue() + '\n';
                }
            }
        }

        if(count == 0) {
            sendMessage("There are no active coordinators for me to list!");
            return;
        }

        sendMessage(result);
    }

    private void runProvisionCommand(String[] args) {
        if(args.length < 3) {
            sendMessage("Usage: @playpen provision <package-id> [properties...]\n" +
                    "Provisions a server on the network.\n" +
                    "The property 'version' will specify the version of the package (default: promoted)\n" +
                    "The property 'coordinator' will specify which coordinator to provision on (default: best fit)\n" +
                    "The property 'name' will specify the name of the server.");
            return;
        }

        String id = args[2];
        String version = "promoted";
        String coordinator = null;
        String serverName = null;
        Map<String, String> properties = new HashMap<>();
        for(int i = 3; i < args.length; i += 2) {
            if(i + 1 >= args.length) {
                sendMessage("Properties must be in the form <key> <value>");
                return;
            }

            String key = args[i];
            String value = args[i+1];

            String lowerKey = key.trim().toLowerCase();
            switch(lowerKey) {
                case "version":
                    version = value;
                    break;

                case "coordinator":
                    coordinator = value;
                    break;

                case "name":
                    serverName = value;
                    break;

                default:
                    properties.put(key, value);
                    break;
            }
        }

        P3Package p3 = Network.get().getPackageManager().resolve(id, version);
        if(p3 == null) {
            sendMessage("Unable to resolve package " + id + "(" + version + ")");
            return;
        }

        ProvisionResult result;
        if(coordinator == null) {
            result = Network.get().provision(p3, serverName, properties);
        }
        else {
            result = Network.get().provision(p3, serverName, properties, coordinator);
        }

        if(result == null) {
            sendMessage("Unable to provision server.");
            return;
        }

        sendMessage("Provision request successful.\n" +
                "  Coordinator uuid: " + result.getCoordinator() + "\n" +
                "  Server uuid: " + result.getServer());
    }

    private void runDeprovisionCommand(String[] args) {
        if(args.length != 4 && args.length != 5) {
            sendMessage("Usage: @playpen deprovision <coordinator> <server> [force=false]\n" +
                    "Deprovisions a server from the network. Coordinator and server arguments accept regex.\n" +
                    "FOr safety, all regex will have ^ prepended and $ appended.");
            return;
        }

        Pattern coordPattern = Pattern.compile('^' + args[2] + '$');
        Pattern serverPattern = Pattern.compile('^' + args[3] + '$');
        boolean force = args.length == 5 && (args[4].trim().toLowerCase().equals("true"));

        if(force)
            sendMessage("Note: deprovisioning via force");

        sendMessage("One moment please...");

        Map<String, List<String>> servers = new HashMap<>();
        for(LocalCoordinator coord : Network.get().getCoordinators().values()) {
            if(coordPattern.matcher(coord.getUuid()).matches() ||
                    (coord.getName() != null && coordPattern.matcher(coord.getName()).matches())) {
                List<String> serverList = new LinkedList<>();
                for(Server server : coord.getServers().values()) {
                    if(serverPattern.matcher(server.getUuid()).matches() ||
                            (server.getName() != null && serverPattern.matcher(server.getName()).matches())) {
                        serverList.add(server.getUuid());
                    }
                }

                if(!serverList.isEmpty())
                    servers.put(coord.getUuid(), serverList);
            }
        }

        if(servers.isEmpty()) {
            sendMessage("I couldn't find any servers to deprovision matching those patterns.");
            return;
        }

        for(Map.Entry<String, List<String>> entry : servers.entrySet()) {
            String coord = entry.getKey();
            for(String server : entry.getValue()) {
                if(!Network.get().deprovision(coord, server, force)) {
                    sendMessage("Unable to send deprovision for " + server + " on coordinator " + coord);
                }
            }
        }

        sendMessage("Deprovision operation complete!");
    }

    private void runShutdownCommand(String[] args) {
        if(args.length != 3) {
            sendMessage("Usage: @playpen shutdown <coordinator>\n" +
                    "Shuts down a single coordinator and any related servers");
            return;
        }

        if(!Network.get().shutdownCoordinator(args[2])) {
            sendMessage("Unable to shutdown coordinator " + args[2]);
        }
    }

    private void runPromoteCommand(String[] args) {
        if(args.length != 4) {
            sendMessage("Usage: @playpen promote <package-id> <package-version>\n" +
                    "Promotes a package.");
            return;
        }

        String id = args[2];
        String version = args[3];
        if(version.equalsIgnoreCase("promoted")) {
            sendMessage("Cannot promote a package of version 'promoted'");
            return;
        }

        P3Package p3 = Network.get().getPackageManager().resolve(id, version);
        if(p3 == null) {
            sendMessage("Sorry, I can't seem to find package " + id + " (" + version + ")");
            return;
        }

        if(Network.get().getPackageManager().promote(p3)) {
            sendMessage("Promoted package " + id + " (" + version + ")");
        }
        else {
            sendMessage("Unable to promote package " + id + " (" + version + ")");
        }
    }

    private void runGenerateKeypairCommand(String[] args) {
        if(args.length != 2) {
            sendMessage("Usage: @playpen generate-keypair\n" +
                    "Generates a new coordinator keypair.");
            return;
        }

        LocalCoordinator coord = Network.get().createCoordinator();
        sendMessage("Here's your new coordinator keypair:\n" +
                "  uuid: " + coord.getUuid() + "\n" +
                "  secret key: " + coord.getKey());
    }

    private void runSendCommand(String[] args) {
        if(args.length < 5) {
            sendMessage("Usage: @playpen send <coordinator> <server> <input...>\n" +
                    "Sends a command to the console of a server." +
                    "Coordinator and server accept regex." +
                    "For safety, all regex will have ^ prepended and $ appended.");
            return;
        }

        Pattern coordPattern = Pattern.compile('^' + args[2] + '$');
        Pattern serverPattern = Pattern.compile('^' + args[3] + "$");
        StringBuilder builder = new StringBuilder();
        for(int i = 4; i < args.length; ++i) {
            builder.append(args[i] + (i == args.length - 1? '\n' : ' '));
        }
        String input = builder.toString();

        sendMessage("One moment please...");

        Map<String, List<String>> servers = new HashMap<>();
        for(LocalCoordinator coord : Network.get().getCoordinators().values()) {
            if(coordPattern.matcher(coord.getUuid()).matches() ||
                    (coord.getName() != null && coordPattern.matcher(coord.getName()).matches())) {
                List<String> serverList = new LinkedList<>();
                for(Server server : coord.getServers().values()) {
                    if(serverPattern.matcher(server.getUuid()).matches() ||
                            (server.getName() != null && serverPattern.matcher(server.getName()).matches())) {
                        serverList.add(server.getUuid());
                    }
                }

                if(!serverList.isEmpty())
                    servers.put(coord.getUuid(), serverList);
            }
        }

        if(servers.isEmpty()) {
            sendMessage("I couldn't find any servers to send input to which match those patterns.");
            return;
        }

        for(Map.Entry<String, List<String>> entry : servers.entrySet()) {
            String coord = entry.getKey();
            for(String server : entry.getValue()) {
                if(Network.get().sendInput(coord, server, input)) {
                    sendMessage("Sent input to server " + server);
                }
                else {
                    sendMessage("Unable to send input to server " + server);
                }
            }
        }

        sendMessage("Send operation completed!");
    }

    private void runFreezeCommand(String[] args) {
        if(args.length != 4) {
            sendMessage("Usage: @playpen freeze <coordinator> <server>\n" +
                    "Sends a command to the console of a server." +
                    "Coordinator and server accept regex.\n" +
                    "For safety, all regex will have ^ prepended and $ appended.");
            return;
        }

        Pattern coordPattern = Pattern.compile('^' + args[2] + '$');
        Pattern serverPattern = Pattern.compile('^' + args[3] + "$");

        sendMessage("One moment please...");

        Map<String, List<String>> servers = new HashMap<>();
        for(LocalCoordinator coord : Network.get().getCoordinators().values()) {
            if(coordPattern.matcher(coord.getUuid()).matches() ||
                    (coord.getName() != null && coordPattern.matcher(coord.getName()).matches())) {
                List<String> serverList = new LinkedList<>();
                for(Server server : coord.getServers().values()) {
                    if(serverPattern.matcher(server.getUuid()).matches() ||
                            (server.getName() != null && serverPattern.matcher(server.getName()).matches())) {
                        serverList.add(server.getUuid());
                    }
                }

                if(!serverList.isEmpty())
                    servers.put(coord.getUuid(), serverList);
            }
        }

        if(servers.isEmpty()) {
            sendMessage("I couldn't find any servers to freeze that match those patterns.");
            return;
        }

        for(Map.Entry<String, List<String>> entry : servers.entrySet()) {
            String coord = entry.getKey();
            for(String server : entry.getValue()) {
                if(Network.get().freezeServer(coord, server)) {
                    sendMessage("Sent freeze to server " + server);
                }
                else {
                    sendMessage("Unable to send freeze to server " + server);
                }
            }
        }

        sendMessage("Freeze operation completed!");
    }

    private void runListPackagesCommand(String[] args) {
        if(args.length != 2) {
            sendMessage("Usage: @playpen list-packages\n" +
                    "Displays a list of all available packages on the network coordinator.");
            return;
        }

        Set<P3Package.P3PackageInfo> p3list = Network.get().getPackageManager().getPackageList();
        if(p3list.size() == 0) {
            sendMessage("There are no packages for me to list!");
        }

        String result = "";
        for(P3Package.P3PackageInfo p3info : p3list) {
            result += p3info.getId() + " (" + p3info.getVersion() + ")\n";
        }

        sendMessage(result);
    }

    private void runListPluginsCommand(String[] args) {
        if(args.length != 2) {
            sendMessage("Usage: @playpen list-packages\n" +
                    "Displays a list of all plugins on the network coordinator.");
            return;
        }

        String result = "";
        for(IPlugin plugin : Network.get().getPluginManager().getPlugins().values()) {
            result += plugin.getSchema().getId() + " (" + plugin.getSchema().getVersion() + ")\n";
        }

        sendMessage(result);
    }
}
