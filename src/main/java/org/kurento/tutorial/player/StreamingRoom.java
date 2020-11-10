package org.kurento.tutorial.player;

import org.kurento.client.MediaPipeline;
import org.kurento.client.PlayerEndpoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StreamingRoom {
    public MediaPipeline getMediaPipeline() {
        return mediaPipeline;
    }

    public void setMediaPipeline(final MediaPipeline mediaPipeline) {
        this.mediaPipeline = mediaPipeline;
    }

    public PlayerEndpoint getPlayerEndpoint() {
        return playerEndpoint;
    }

    public void setPlayerEndpoint(final PlayerEndpoint playerEndpoint) {
        this.playerEndpoint = playerEndpoint;
    }

    private MediaPipeline mediaPipeline;
    private PlayerEndpoint playerEndpoint;
    
    public void addUser(final UserSession user) {
        safeList.add(user);
    }

    public List<UserSession> getUserInRoom() {
        return Collections.unmodifiableList(safeList);
    }

    private List<UserSession> safeList = Collections.synchronizedList(new ArrayList<>());
    
    public void removeUserInRoom(final UserSession session) {
        safeList.remove(session);
    }
}
