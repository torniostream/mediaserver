package org.kurento.tutorial.player;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.util.UUID;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StreamingRoom {
    private transient DispatcherOneToMany roomDispatcher;
    private transient MediaPipeline mediaPipeline;
    private transient PlayerEndpoint playerEndpoint;

    private UserSession admin;
    private List<UserSession> safeList = Collections.synchronizedList(new ArrayList<>());

    private final String uuid;

    public StreamingRoom(final KurentoClient kurento, final UserSession userAdmin, final String mediaUri) {
        uuid = UUID.randomUUID().toString();
        
        mediaPipeline = kurento.createMediaPipeline();
        roomDispatcher = new DispatcherOneToMany.Builder(mediaPipeline).build();
        playerEndpoint = new PlayerEndpoint.Builder(mediaPipeline, mediaUri).build();

        HubPort playerHub = new HubPort.Builder(roomDispatcher).build();
        playerEndpoint.connect(playerHub);

        playerEndpoint.setMaxOutputBitrate(Integer.MAX_VALUE);
        admin = userAdmin;
        addUser(userAdmin);

        roomDispatcher.setSource(playerHub);
    }
    
    public String getUUID() {
        return this.uuid;
    }

    private void notifyUsersNewEntry(final UserSession user) {
        for (final UserSession us: safeList) {
            if (us == user) {
                continue;
            }
            // Notify other users
            JsonObject response = new JsonObject();
            response.addProperty("id", "newUser");

            Gson gson = new Gson();
            JsonElement who = gson.toJsonTree(user);

            response.add("user", who);
            sendMessage(us.getWs(), response.toString());
        }
    }

    private void notifyUsersExit(final UserSession user) {
        for (final UserSession us: safeList) {
            if (us == user) {
                continue;
            }
            // Notify other users
            JsonObject response = new JsonObject();
            response.addProperty("id", "userLeft");

            Gson gson = new Gson();
            JsonElement who = gson.toJsonTree(user);

            response.add("user", who);
            sendMessage(us.getWs(), response.toString());
        }
    }

    private void meetTheOtherUsers(final UserSession user) {
        for (final UserSession us: safeList) {
            if (us == user) {
                continue;
            }
            JsonObject response = new JsonObject();
            response.addProperty("id", "newUser");
            Gson gson = new Gson();
            JsonElement who = gson.toJsonTree(user);

            response.add("user", who);
            sendMessage(user.getWs(), response.toString());
        }
    }
    
    public boolean addUser(final UserSession user) {
        final WebRtcEndpoint webRtcEpUser = new WebRtcEndpoint.Builder(mediaPipeline).build();
        user.setWebRtcEndpoint(webRtcEpUser);

        HubPort hubPort = new HubPort.Builder(roomDispatcher).build();
        user.setHubPort(hubPort);

        webRtcEpUser.setMaxVideoRecvBandwidth(20000000);
        //webRtcEpUser.setMinVideoRecvBandwidth(300000);
        hubPort.setMaxOutputBitrate(20000000);
        //hubPort.setMinOutputBitrate(800000);
        hubPort.connect(webRtcEpUser);

        // Make the users meet
        notifyUsersNewEntry(user);
        meetTheOtherUsers(user);

        // Send back the UUID
        sendUUID(user.getWs());
        
        user.setRoom(this);
        return safeList.add(user);
    }
    
    public Boolean removeUser(final UserSession user) {
        if (!safeList.contains(user)) {
            return false;
        }
        
        if (!safeList.remove(user)) {
            return false;
        }

        user.getHubPort().disconnect(user.getWebRtcEndpoint());
        user.getHubPort().release();
        user.getWebRtcEndpoint().release();
        
        if (safeList.isEmpty()) {
            mediaPipeline.release();
        }

        notifyUsersExit(user);
        
        return true;
    }
    
    public PlayerEndpoint getPlayerEndpoint() {
        return playerEndpoint;
    }
    
    public void pause(final UserSession initiator) {
        playerEndpoint.pause();
        for (final UserSession us: safeList) {
            if (us == initiator) {
                continue;
            }
            sendPause(us.getWs(), initiator);
        }
    }
    
    public void resume(final UserSession initiator) {
        playerEndpoint.play();
        for (final UserSession us: safeList) {
            if (us == initiator) {
                continue;
            }
            sendResume(us.getWs(), initiator);
        }
    }

    private void sendPause(WebSocketSession session, final UserSession initiator) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "paused");

        Gson gson = new Gson();
        JsonElement who = gson.toJsonTree(initiator);

        response.add("initiator", who);
        sendMessage(session, response.toString());
    }

    private void sendResume(WebSocketSession session, final UserSession initiator) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "resumed");
        Gson gson = new Gson();
        JsonElement who = gson.toJsonTree(initiator);

        response.add("initiator", who);
        sendMessage(session, response.toString());
    }

    private void sendUUID(WebSocketSession session) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "uuid");
        response.addProperty("uuid", this.getUUID());
        sendMessage(session, response.toString());
    }

    private synchronized void sendMessage(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage(message));
        } catch (IOException e) {
            System.out.println("Error in sending message");
        }
    }
}
