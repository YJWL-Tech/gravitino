/*
 * Copyright 2023 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.authentication;

import static com.datastrato.gravitino.server.authentication.TokenAuthConfig.SERVER_URI;

import com.datastrato.gravitino.Config;
import com.datastrato.gravitino.auth.AuthConstants;
import com.datastrato.gravitino.exceptions.UnauthorizedException;
import com.datastrato.gravitino.json.JsonUtils;
import com.sun.security.auth.UserPrincipal;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;

public class TokenAuthenticator implements Authenticator {
  private String tokenServerUri;
  private CloseableHttpClient httpClient;

  @Override
  public boolean isDataFromToken() {
    return true;
  }

  @SuppressWarnings("deprecation")
  @Override
  public Principal authenticateToken(byte[] tokenData) {
    if (tokenData == null) {
      throw new UnauthorizedException("Empty token authorization header");
    }
    String authData = new String(tokenData, StandardCharsets.UTF_8);
    if (StringUtils.isBlank(authData)
        || !authData.startsWith(AuthConstants.AUTHORIZATION_TOKEN_HEADER)) {
      throw new UnauthorizedException("Invalid token authorization header");
    }
    String token = authData.substring(AuthConstants.AUTHORIZATION_TOKEN_HEADER.length());
    HttpGet httpGet = buildTokenRequest(token);
    try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
      if (response.getEntity() == null) {
        throw new UnauthorizedException("Unable to authenticate from token server");
      }
      String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
      if (responseBody == null) {
        throw new UnauthorizedException("Unable to authenticate from token server");
      }
      if (!isSuccessful(response)) {
        throw new UnauthorizedException(
            "Unable to authenticate with token: %s, code: %s", token, response.getCode());
      }
      DataWorkshopUser dataWorkshopUser =
          JsonUtils.objectMapper().readValue(responseBody, DataWorkshopUser.class);
      return new UserPrincipal(
          String.format(
              "%s:%s:%s",
              dataWorkshopUser.getUsername(),
              dataWorkshopUser.getWorkspaceId(),
              dataWorkshopUser.getRole()));
    } catch (IOException | ParseException e) {
      throw new UnauthorizedException(e, "Unable to authenticate with token: %s", token);
    }
  }

  @Override
  public void initialize(Config config) throws RuntimeException {
    this.tokenServerUri = config.get(SERVER_URI);
    this.httpClient = HttpClients.createDefault();
  }

  private HttpGet buildTokenRequest(String token) {
    try {
      URI uri = new URIBuilder(tokenServerUri).addParameter("token", token).build();
      return new HttpGet(uri);
    } catch (URISyntaxException e) {
      throw new UnauthorizedException(e, "Error building token uri");
    }
  }

  private boolean isSuccessful(CloseableHttpResponse response) {
    int code = response.getCode();
    return code == HttpStatus.SC_OK
        || code == HttpStatus.SC_ACCEPTED
        || code == HttpStatus.SC_NO_CONTENT;
  }
}