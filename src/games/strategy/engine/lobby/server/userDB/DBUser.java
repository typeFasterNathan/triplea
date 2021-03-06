package games.strategy.engine.lobby.server.userDB;

import java.io.Serializable;
import java.util.Date;

public class DBUser implements Serializable {
  private static final long serialVersionUID = -5289923058375302916L;
  private final String m_name;
  private final String m_email;
  private final boolean m_isAdmin;
  private final Date m_lastLogin;
  private final Date m_joined;

  public DBUser(final String name, final String email, final boolean isAdmin, final Date lastLogin, final Date joined) {
    m_name = name;
    m_email = email;
    m_isAdmin = isAdmin;
    m_lastLogin = lastLogin;
    m_joined = joined;
  }

  public String getEmail() {
    return m_email;
  }

  public boolean isAdmin() {
    return m_isAdmin;
  }

  public Date getJoined() {
    return m_joined;
  }

  public Date getLastLogin() {
    return m_lastLogin;
  }

  public String getName() {
    return m_name;
  }
}
