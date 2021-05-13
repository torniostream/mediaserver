package org.kurento.tutorial.player;

import org.kurento.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

public class UserSession {
  private static final Logger log = LoggerFactory.getLogger(UserSession.class);
  private transient WebRtcEndpoint webRtcEndpoint;
  private transient StreamingRoom room;
  private transient WebSocketSession ws;
  private transient HubPort hubPort;
  private String nickname;
  private Boolean isAdmin = false;
  private Avatar avatar = new Avatar();

  private class Avatar {
    private Integer id;
    private String path;
  }

  public Integer getAvatarId() {
    return avatar.id;
  }

  public String getAvatarPath() {
    return avatar.path;
  }

  public Boolean getInhibited() {
    return inhibited;
  }

  public void setInhibited(final Boolean inhibited) {
    this.inhibited = inhibited;
  }

  private Boolean inhibited = false;
  
  public UserSession(final WebSocketSession ws, final String nick) {
    this.ws = ws;
    this.nickname = nick;
  }
  
  public String getNick() {
    return this.nickname;
  }

  public Boolean validate() {
    if (this.ws == null) {
      return false;
    }

    if (this.nickname == null || this.nickname.equals("")) {
      return false;
    }

    if (this.avatar == null || this.avatar.id == null || this.avatar.id == 0) {
      return false;
    }

    return true;
  }

  public Boolean isAdmin() { return isAdmin; }

  public void setIsAdmin(final Boolean isAdmin) { this.isAdmin = isAdmin; }

  public WebRtcEndpoint getWebRtcEndpoint() {
    return webRtcEndpoint;
  }

  public void setWebRtcEndpoint(WebRtcEndpoint webRtcEndpoint) {
    this.webRtcEndpoint = webRtcEndpoint;
  }

  public void addCandidate(IceCandidate candidate) {
    webRtcEndpoint.addIceCandidate(candidate);
  }

  public StreamingRoom getRoom() {
    return room;
  }

  public void setRoom(final StreamingRoom room) {
    this.room = room;
  }

  public WebSocketSession getWs() {
    return ws;
  }

  public void setWs(WebSocketSession ws) {
    this.ws = ws;
  }

  public HubPort getHubPort() {
    return hubPort;
  }

  public void setHubPort(HubPort hubPort) {
    this.hubPort = hubPort;
  }
}
