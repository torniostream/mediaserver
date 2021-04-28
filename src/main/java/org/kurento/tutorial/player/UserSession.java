package org.kurento.tutorial.player;

import org.kurento.client.*;
import org.springframework.web.socket.WebSocketSession;

public class UserSession {

  private WebRtcEndpoint webRtcEndpoint;
  private StreamingRoom room;
  private WebSocketSession ws;
  private HubPort hubPort;
  private String nick;
  
  public UserSession(final WebSocketSession ws, final String nick) {
    this.ws = ws;
    this.nick = nick;
  }
  
  public String getNick() {
    return this.nick;
  }

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
