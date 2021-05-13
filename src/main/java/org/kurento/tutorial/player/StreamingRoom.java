package org.kurento.tutorial.player;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.kurento.commons.exception.KurentoException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.util.UUID;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

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

    public List<UserSession> getUserList() {
        return Collections.unmodifiableList(this.safeList);
    }
    
    public String getUUID() {
        return this.uuid;
    }

    private UserSession getUserFromUsername(final String username) {
        for (UserSession us: safeList) {
            if (us.getNick().equals(username)) return us;
        }

        return null;
    }

    public void setInhibitUser(final UserSession initiator, final String targetUsername, final Boolean status) {
        if (initiator == null) {
            return;
        }

        if (!initiator.isAdmin()) {
            sendError(initiator.getWs(), "You're not authorized to inhibit another user because you're not a room administrator.");
            return;
        }

        UserSession target = getUserFromUsername(targetUsername);
        if (target == null) {
            sendError(initiator.getWs(), "The target user you want to inhibit does not exist.");
            return;
        }

        target.setInhibited(status);
        notifyUsersInhibited(target, status);
    }

    private void notifyUsersInhibited(final UserSession user, final Boolean inhibited) {
        for (final UserSession us: safeList) {
            JsonObject response = new JsonObject();

            String operation = "userUninhibited";
            if (inhibited) {
                operation = "userInhibited";
            }

            response.addProperty("id", operation);

            Gson gson = new Gson();
            JsonElement who = gson.toJsonTree(user);

            response.add("user", who);
            sendMessage(us.getWs(), response.toString());
        }
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
        if (getUserFromUsername(user.getNick()) != null) {
            sendError(user.getWs(), "There's already an user with your name.");
            return false;
        }

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
        VideoInfo videoInfo = this.playerEndpoint.getVideoInfo();

        JsonObject response1 = new JsonObject();
        response1.addProperty("id", "videoInfo");
        response1.addProperty("isSeekable", videoInfo.getIsSeekable());
        response1.addProperty("initSeekable", videoInfo.getSeekableInit());
        response1.addProperty("endSeekable", videoInfo.getSeekableEnd());
        response1.addProperty("videoDuration", videoInfo.getDuration());
        sendMessage(user.getWs(), response1.toString());

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

        notifyUsersExit(user);

        if (safeList.isEmpty()) {
            mediaPipeline.release();
        }

        if (!safeList.isEmpty() && this.admin.equals(user)) {
            int randomIndex = ThreadLocalRandom.current().nextInt(this.safeList.size()) % this.safeList.size();
            setAdmin(this.safeList.get(randomIndex));
        }
        
        return true;
    }

    public void setAdmin(final UserSession newAdmin) {
        this.admin = newAdmin;
        newAdmin.setIsAdmin(true);
        notifyUsersNewAdmin(this.admin);
    }

    private void notifyUsersNewAdmin(final UserSession newAdmin) {
        for (final UserSession us: safeList) {
            // Notify other users
            JsonObject response = new JsonObject();
            response.addProperty("id", "newAdmin");

            Gson gson = new Gson();
            JsonElement who = gson.toJsonTree(newAdmin);

            response.add("user", who);
            sendMessage(us.getWs(), response.toString());
        }
    }

    public PlayerEndpoint getPlayerEndpoint() {
        return playerEndpoint;
    }
    
    public void pause(final UserSession initiator) {
        if (initiator.getInhibited()) {
            sendError(initiator.getWs(), "You're inhibited. You cannot perform this operation.");
            return;
        }

        playerEndpoint.pause();

        for (final UserSession us: safeList) {
            if (us == initiator) {
                continue;
            }
            sendPause(us.getWs(), initiator);
        }
    }
    
    public void resume(final UserSession initiator) {
        if (initiator.getInhibited()) {
            sendError(initiator.getWs(), "You're inhibited. You cannot perform this operation.");
            return;
        }

        playerEndpoint.play();
        for (final UserSession us: safeList) {
            if (us == initiator) {
                continue;
            }
            sendResume(us.getWs(), initiator);
        }
    }

    public void seek(final UserSession initiator, final long position) {
        if (initiator.getInhibited()) {
            sendError(initiator.getWs(), "You're inhibited. You cannot perform this operation.");
            return;
        }

        try {
            playerEndpoint.setPosition(position);
            for (final UserSession us: safeList) {
                if (us == initiator) {
                    continue;
                }
                sendSeek(us.getWs(), initiator, position);
            }
        } catch (KurentoException e) {
            JsonObject response = new JsonObject();
            response.addProperty("id", "seek");
            response.addProperty("message", "Seek failed");
            sendMessage(initiator.getWs(), response.toString());
        }
    }

    private void sendSeek(final WebSocketSession session, final UserSession initiator, final long newPosition) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "seek");

        Gson gson = new Gson();
        JsonElement who = gson.toJsonTree(initiator);

        response.addProperty("newPosition", newPosition);
        response.add("initiator", who);
        sendMessage(session, response.toString());
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

    private void sendMessage(WebSocketSession session, String message) {
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(message));
            } catch (IOException e) {
                System.out.println("Error in sending message");
            }
        }
    }

    private void sendError(WebSocketSession session, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "error");
        response.addProperty("message", message);
        sendMessage(session, response.toString());
    }
}
