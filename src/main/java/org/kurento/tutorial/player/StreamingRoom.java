package org.kurento.tutorial.player;

import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StreamingRoom {
    private DispatcherOneToMany roomDispatcher;
    private MediaPipeline mediaPipeline;
    private PlayerEndpoint playerEndpoint;

    private UserSession admin;
    private List<UserSession> safeList = Collections.synchronizedList(new ArrayList<>());


    public StreamingRoom(final KurentoClient kurento, final UserSession userAdmin, final String mediaUri) {
        mediaPipeline = kurento.createMediaPipeline();
        roomDispatcher = new DispatcherOneToMany.Builder(mediaPipeline).build();
        playerEndpoint = new PlayerEndpoint.Builder(mediaPipeline, mediaUri).useEncodedMedia().build();

        HubPort playerHub = new HubPort.Builder(roomDispatcher).build();
        playerEndpoint.connect(playerHub);

        playerEndpoint.setMaxOutputBitrate(Integer.MAX_VALUE);
        admin = userAdmin;
        addUser(userAdmin);

        roomDispatcher.setSource(playerHub);
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
        
        return true;
    }
    
    public PlayerEndpoint getPlayerEndpoint() {
        return playerEndpoint;
    }
    
    public void pause() {
        playerEndpoint.pause();
        for (final UserSession us: safeList) {
            sendPause(us.getWs());
        }
    }
    
    public void resume() {
        playerEndpoint.play();
        for (final UserSession us: safeList) {
            sendResume(us.getWs());
        }
    }

    private void sendPause(WebSocketSession session) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "paused");
        sendMessage(session, response.toString());
    }

    private void sendResume(WebSocketSession session) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "resumed");
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
