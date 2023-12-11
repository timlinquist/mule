/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.connectivity.oauth.clientcredentials;

import org.mule.oauth.client.api.ClientCredentialsOAuthDancer;
import org.mule.oauth.client.api.listener.ClientCredentialsListener;
import org.mule.oauth.client.api.state.ResourceOwnerOAuthContext;
import org.mule.runtime.extension.api.connectivity.oauth.ClientCredentialsState;
import org.mule.runtime.module.extension.internal.runtime.connectivity.oauth.exception.TokenInvalidatedException;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * An implementation of {@link ClientCredentialsState} which registers an {@link ClientCredentialsListener} in order to get
 * updated state when a refresh token operation is completed.
 *
 * @since 4.2.1
 */
public class UpdatingClientCredentialsState
    implements ClientCredentialsState, org.mule.sdk.api.connectivity.oauth.ClientCredentialsState {

  private final ClientCredentialsOAuthDancer dancer;
  private ClientCredentialsState delegate;
  private boolean invalidated = false;
  private ClientCredentialsListener clientCredentialsListener;

  public UpdatingClientCredentialsState(ClientCredentialsOAuthDancer dancer,
                                        ResourceOwnerOAuthContext initialContext,
                                        Consumer<ResourceOwnerOAuthContext> onUpdate) {
    this.dancer = dancer;
    updateDelegate(initialContext);
    clientCredentialsListener = new ClientCredentialsListener() {

      @Override
      public void onTokenRefreshed(ResourceOwnerOAuthContext context) {
        updateDelegate(context);
        onUpdate.accept(context);
      }

      @Override
      public void onTokenInvalidated() {
        invalidated = true;
      }
    };
    dancer.addListener(clientCredentialsListener);
  }

  private void updateDelegate(ResourceOwnerOAuthContext initialContext) {
    delegate = new ImmutableClientCredentialsState(initialContext.getAccessToken(), initialContext.getExpiresIn());
    invalidated = false;
  }

  @Override
  public String getAccessToken() {
    if (invalidated) {
      try {
        dancer.accessToken().get();
        updateDelegate(dancer.getContext());
      } catch (Exception e) {
        throw new TokenInvalidatedException("Access Token has been invalidated and failed to obtain a new one", e);
      }
    }
    return delegate.getAccessToken();
  }

  @Override
  public Optional<String> getExpiresIn() {
    return delegate.getExpiresIn();
  }

  public void deregisterListener() {
    dancer.removeListener(clientCredentialsListener);
  }
}
