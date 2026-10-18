package com.securegate.iam.identity.infrastructure;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.securegate.iam.identity.application.port.AuthenticatedIdentity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Explicit Jackson type support for this class only; password material is never serialized. */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
public final class LoginPrincipal
    implements UserDetails, AuthenticatedIdentity, CredentialsContainer {
  private final String id;
  private final long version;
  private final boolean active;
  private transient String password;

  @JsonCreator
  public LoginPrincipal(
      @JsonProperty("id") String id,
      @JsonProperty("version") long version,
      @JsonProperty("active") boolean active) {
    this.id = UUID.fromString(id).toString();
    if (version < 0) {
      throw new IllegalArgumentException("Invalid identity version.");
    }
    this.version = version;
    this.active = active;
  }

  public static LoginPrincipal forLogin(String id, long version, boolean active, String hash) {
    var principal = new LoginPrincipal(id, version, active);
    principal.password = hash;
    return principal;
  }

  @JsonProperty("id")
  public String id() {
    return id;
  }

  @JsonProperty("version")
  @Override
  public long version() {
    return version;
  }

  @JsonProperty("active")
  public boolean active() {
    return active;
  }

  @JsonIgnore
  @Override
  public String getName() {
    return id;
  }

  @JsonIgnore
  @Override
  public String getUsername() {
    return id;
  }

  @JsonIgnore
  @Override
  public String getPassword() {
    return password;
  }

  @JsonIgnore
  @Override
  public boolean isEnabled() {
    return active;
  }

  @JsonIgnore
  @Override
  public boolean isAccountNonLocked() {
    return active;
  }

  @JsonIgnore
  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @JsonIgnore
  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @JsonIgnore
  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("BROWSER_USER"));
  }

  @Override
  public void eraseCredentials() {
    password = null;
  }

  @Override
  public String toString() {
    return "LoginPrincipal[REDACTED]";
  }
}
