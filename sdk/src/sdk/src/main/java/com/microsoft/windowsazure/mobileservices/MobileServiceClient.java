/*
Copyright (c) Microsoft Open Technologies, Inc.
All Rights Reserved
Apache 2.0 License

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

See the Apache Version 2.0 License for specific language governing permissions and limitations under the License.
 */

/**
 * MobileServiceClient.java
 */
package com.microsoft.windowsazure.mobileservices;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Pair;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.SerializedName;
import com.microsoft.windowsazure.mobileservices.authentication.CustomTabsLoginManager;
import com.microsoft.windowsazure.mobileservices.authentication.LoginManager;
import com.microsoft.windowsazure.mobileservices.authentication.MobileServiceAuthenticationProvider;
import com.microsoft.windowsazure.mobileservices.authentication.MobileServiceUser;
import com.microsoft.windowsazure.mobileservices.http.HttpConstants;
import com.microsoft.windowsazure.mobileservices.http.MobileServiceConnection;
import com.microsoft.windowsazure.mobileservices.http.MobileServiceHttpClient;
import com.microsoft.windowsazure.mobileservices.http.NextServiceFilterCallback;
import com.microsoft.windowsazure.mobileservices.http.OkHttpClientFactory;
import com.microsoft.windowsazure.mobileservices.http.OkHttpClientFactoryImpl;
import com.microsoft.windowsazure.mobileservices.http.ServiceFilter;
import com.microsoft.windowsazure.mobileservices.http.ServiceFilterRequest;
import com.microsoft.windowsazure.mobileservices.http.ServiceFilterResponse;
import com.microsoft.windowsazure.mobileservices.notifications.MobileServicePush;
import com.microsoft.windowsazure.mobileservices.table.MobileServiceJsonTable;
import com.microsoft.windowsazure.mobileservices.table.MobileServiceTable;
import com.microsoft.windowsazure.mobileservices.table.serialization.DateSerializer;
import com.microsoft.windowsazure.mobileservices.table.serialization.JsonEntityParser;
import com.microsoft.windowsazure.mobileservices.table.serialization.LongSerializer;
import com.microsoft.windowsazure.mobileservices.table.sync.MobileServiceJsonSyncTable;
import com.microsoft.windowsazure.mobileservices.table.sync.MobileServiceSyncContext;
import com.microsoft.windowsazure.mobileservices.table.sync.MobileServiceSyncTable;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;

/**
 * Entry point for Microsoft Azure Mobile app interactions
 */
public class MobileServiceClient {
    /**
     * UTF-8 encoding
     */
    public static final String UTF8_ENCODING = "UTF-8";
    /**
     * Google account type
     */
    public static final String GOOGLE_ACCOUNT_TYPE = "com.google";
    /**
     * Authentication token type required for client login
     */
    public static final String GOOGLE_USER_INFO_SCOPE = "oauth2:https://www.googleapis.com/auth/userinfo.profile";
    /**
     * Custom API Url
     */
    private static final String CUSTOM_API_URL = "api/";

    /**
     * Chrome Custom Tabs Login methods
     */
    private CustomTabsLoginManager mCustomTabsLoginManager;

    /**
     * LoginManager used for login methods
     */
    private LoginManager mLoginManager;
    /**
     * Mobile Service URL
     */
    private URL mAppUrl;
    /**
     * Flag to indicate that a login operation is in progress
     */
    private boolean mLoginInProgress;
    /**
     * The current authenticated user
     */
    private MobileServiceUser mCurrentUser;
    /**
     * Service filter to execute the request
     */
    private ServiceFilter mServiceFilter;
    /**
     * GsonBuilder used to in JSON Serialization/Deserialization
     */
    private GsonBuilder mGsonBuilder;
    /**
     * Context where the MobileServiceClient is created
     */
    private Context mContext;
    /**
     * AndroidHttpClientFactory used for request execution
     */
    private OkHttpClientFactory mOkHttpClientFactory;
    /**
     * MobileServicePush used for push notifications
     */
    private MobileServicePush mPush;

    /*
    *  prefix for login endpoints. If not set defaults to /.auth/login
    */
    private String mLoginUriPrefix;

    /*
    *    Alternate Host URI for login
    */
    private URL mAlternateLoginHost;

    /*
     *  returns the Alternate Host URI for login
     */
    public URL getAlternateLoginHost() {
        return mAlternateLoginHost;
    }

    private static URL normalizeUrl(URL appUrl) {
        URL normalizedAppURL = appUrl;

        if (normalizedAppURL.getPath().isEmpty()) {
            try {
                normalizedAppURL = new URL(appUrl.toString() + "/");
            } catch (MalformedURLException e) {
                // This exception won't happen, since it's just adding a
                // trailing "/" to a valid URL
            }
        }
        return normalizedAppURL;
    }

    /**
     * Sets the Alternate Host URI for login
     *
     * @param alternateLoginHost Alternate Host URI for login
     */
    public void setAlternateLoginHost(URL alternateLoginHost) {
        if (alternateLoginHost == null) {
            mAlternateLoginHost = mAppUrl;
        } else if (alternateLoginHost.getProtocol().toUpperCase().equals(HttpConstants.HttpsProtocol) && (alternateLoginHost.getPath().length() == 0 || alternateLoginHost.getPath().equals(Character.toString(Slash)))) {
            mAlternateLoginHost = normalizeUrl(alternateLoginHost);
        } else {
            throw new IllegalArgumentException(String.format("%s is invalid.AlternateLoginHost must be a valid https URI with hostname only", alternateLoginHost.toString()));
        }
    }

    /*
     *  returns the prefix for login endpoints. If not set defaults to /.auth/login
     */
    public String getLoginUriPrefix() {
        return mLoginUriPrefix;
    }

    /**
     * Sets the prefix for login endpoints
     *
     * @param loginUriPrefix prefix for login endpoints
     */
    public void setLoginUriPrefix(String loginUriPrefix) {
        if (loginUriPrefix == null) {
            return;
        }
        mLoginUriPrefix = AddLeadingSlash(loginUriPrefix);
    }

    private static final char Slash = '/';

    /*
        Adds Leading slash to the string if not present
    */
    private static String AddLeadingSlash(String uri) {
        if (uri == null) {
            throw new IllegalArgumentException("uri");
        }

        if (!uri.startsWith(Character.toString(Slash))) {
            uri = Slash + uri;
        }

        return uri;
    }

    /**
     * MobileServiceSyncContext used for synchronization between local and
     * remote databases.
     */
    private MobileServiceSyncContext mSyncContext;

    /**
     * Constructor for the MobileServiceClient
     *
     * @param client An existing MobileServiceClient
     */
    private MobileServiceClient(MobileServiceClient client) {
        initialize(client.getAppUrl(), client.getCurrentUser(), client.getGsonBuilder(), client.getContext(),
                client.getOkHttpClientFactory(), client.getLoginUriPrefix(), client.getAlternateLoginHost());
    }

    /**
     * Constructor for the MobileServiceClient
     *
     * @param appUrl  Mobile Service URL
     * @param context The Context where the MobileServiceClient is created
     * @throws java.net.MalformedURLException
     */
    public MobileServiceClient(String appUrl, Context context) throws MalformedURLException {
        this(new URL(appUrl), context);
    }

    /**
     * Constructor for the MobileServiceClient
     *
     * @param appUrl  Mobile Service URL
     * @param context The Context where the MobileServiceClient is created
     */
    public MobileServiceClient(URL appUrl, Context context) {
        GsonBuilder gsonBuilder = createMobileServiceGsonBuilder();
        initialize(appUrl, null, gsonBuilder, context, new OkHttpClientFactoryImpl(), null, null);
    }

    /**
     * Constructor for the MobileServiceClient
     *
     * @param appUrl  Mobile Service URL
     * @param context The Context where the MobileServiceClient is created
     * @throws java.net.MalformedURLException
     */
    public MobileServiceClient(String appUrl, Context context, GsonBuilder gsonBuilder) throws MalformedURLException {
        this(new URL(appUrl), context, gsonBuilder);
    }

    /**
     * Constructor for the MobileServiceClient
     *
     * @param appUrl  Mobile Service URL
     * @param context The Context where the MobileServiceClient is created
     */
    public MobileServiceClient(URL appUrl, Context context, GsonBuilder gsonBuilder) {
        initialize(appUrl, null, gsonBuilder, context, new OkHttpClientFactoryImpl(), null, null);
    }

    /**
     * Creates a GsonBuilder with custom serializers to use with Microsoft Azure
     * Mobile Services
     *
     * @return
     */
    private static GsonBuilder createMobileServiceGsonBuilder() {
        GsonBuilder gsonBuilder = new GsonBuilder();

        // Register custom date serializer/deserializer
        gsonBuilder.registerTypeAdapter(Date.class, new DateSerializer());
        LongSerializer longSerializer = new LongSerializer();
        gsonBuilder.registerTypeAdapter(Long.class, longSerializer);
        gsonBuilder.registerTypeAdapter(long.class, longSerializer);

        gsonBuilder.excludeFieldsWithModifiers(Modifier.FINAL, Modifier.TRANSIENT, Modifier.STATIC);
        gsonBuilder.serializeNulls(); // by default, add null serialization

        return gsonBuilder;
    }

    /**
     * Return Custom Tabs login manager
     */
    public CustomTabsLoginManager getCustomTabsLoginManager() {
        return mCustomTabsLoginManager;
    }

    /**
     * Refreshes access token with the identity provider for the logged in user.
     * @return Refreshed Mobile Service user
     */
    public ListenableFuture<MobileServiceUser> refreshUser() {
        return mLoginManager.refreshUser();
    }

    /**
     * Refreshes access token with the identity provider for the logged in user.
     * @param callback The callback to invoke when the authentication process finishes
     */
    public void refreshUser(final UserAuthenticationCallback callback) {
        mLoginManager.refreshUser(callback);
    }

    /**
     * Invokes an interactive authentication process using the specified
     * Authentication Provider
     *
     * @param provider The provider used for the authentication process
     * @param uriScheme The URL scheme of the application
     * @param authRequestCode The request code that will be returned in onActivityResult() when
     *                        the login flow completes and activity exits
     */
    public void login(String provider, String uriScheme, int authRequestCode) {
        this.mCustomTabsLoginManager.authenticate(provider, uriScheme, null, mContext, authRequestCode);
    }

    /**
     * Invokes an interactive authentication process using the specified
     * Authentication Provider
     *
     * @param provider The provider used for the authentication process
     * @param uriScheme The URL scheme of the application
     * @param authRequestCode The request code that will be returned in onActivityResult() when
     *                        the login flow completes and activity exits
     */
    public void login(MobileServiceAuthenticationProvider provider, String uriScheme, int authRequestCode) {
        this.mCustomTabsLoginManager.authenticate(provider.toString(), uriScheme, null, mContext, authRequestCode);
    }

    /**
     * Invokes an interactive authentication process using the specified
     * Authentication Provider
     *
     * @param provider The provider used for the authentication process
     * @param uriScheme The URL scheme of the application
     * @param authRequestCode The request code that will be returned in onActivityResult() when
     *                        the login flow completes and activity exits
     * @param parameters Additional parameters for the authentication process
     */
    public void login(String provider, String uriScheme, int authRequestCode, HashMap<String, String> parameters) {
        this.mCustomTabsLoginManager.authenticate(provider, uriScheme, parameters, mContext, authRequestCode);
    }

    /**
     * Invokes an interactive authentication process using the specified
     * Authentication Provider
     *
     * @param provider The provider used for the authentication process
     * @param uriScheme The URL scheme of the application
     * @param authRequestCode The request code that will be returned in onActivityResult() when
     *                        the login flow completes and activity exits
     * @param parameters Additional parameters for the authentication process
     */
    public void login(MobileServiceAuthenticationProvider provider, String uriScheme, int authRequestCode, HashMap<String, String> parameters) {
        this.mCustomTabsLoginManager.authenticate(provider.toString(), uriScheme, parameters, mContext, authRequestCode);
    }

    /**
     * Helper method which can be used in the onActivityResult() of your activity that started the server-direct login
     * flow, to retrieve the {@link MobileServiceActivityResult} which contains the login status and the error message
     * from the login.
     *
     * @param data The Intent that returns the login result back to the caller
     * @return authenticated Mobile Service user
     */
    public MobileServiceActivityResult onActivityResult(Intent data) {
        MobileServiceUser user = null;

        String userId = data.getStringExtra(CustomTabsLoginManager.KEY_LOGIN_USER_ID);
        String authenticationToken = data.getStringExtra(CustomTabsLoginManager.KEY_LOGIN_AUTHENTICATION_TOKEN);

        if (userId != null && authenticationToken != null) {
            user = new MobileServiceUser(userId);
            user.setAuthenticationToken(authenticationToken);
            mCurrentUser = user;
            return new MobileServiceActivityResult(true, null);
        } else {
            return new MobileServiceActivityResult(false, data.getStringExtra(CustomTabsLoginManager.KEY_LOGIN_ERROR));
        }
    }

    /**
     * Invokes an interactive authentication process using the specified
     * Authentication Provider
     *
     * @param provider The provider used for the authentication process
     * @deprecated use {@link #login(MobileServiceAuthenticationProvider provider, String uriScheme, int authRequestCode)} instead
     */
    public ListenableFuture<MobileServiceUser> login(MobileServiceAuthenticationProvider provider) {
        return login(provider.toString());
    }

    /**
     * Invokes an interactive authentication process using the specified
     * Authentication Provider
     *
     * @param provider   The provider used for the authentication process
     * @param parameters Additional parameters for the authentication process
     * @deprecated use {@link #login(MobileServiceAuthenticationProvider provider, HashMap<String, String> parameters, String uriScheme, int authRequestCode)} instead
     */
    public ListenableFuture<MobileServiceUser> login(MobileServiceAuthenticationProvider provider, HashMap<String, String> parameters) {
        return login(provider.toString(), parameters);
    }

    /**
     * Invokes an interactive authentication process using the specified
     * Authentication Provider
     *
     * @param provider The provider used for the authentication process
     * @param callback Callback to invoke when the authentication process finishes
     * @deprecated use {@link #login(MobileServiceAuthenticationProvider provider, String uriScheme, int authRequestCode)} instead
     */
    public void login(MobileServiceAuthenticationProvider provider, UserAuthenticationCallback callback) {
        login(provider.toString(), callback);
    }


    /**
     * Invokes an interactive authentication process using the specified
     * Authentication Provider
     *
     * @param provider   The provider used for the authentication process
     * @param parameters Additional parameters for the authentication process
     * @param callback   Callback to invoke when the authentication process finishes
     * @deprecated use {@link #login(MobileServiceAuthenticationProvider provider, HashMap<String, String> parameters, String uriScheme, int authRequestCode)} instead
     */
    public void login(MobileServiceAuthenticationProvider provider, HashMap<String, String> parameters, UserAuthenticationCallback callback) {
        login(provider.toString(), parameters, callback);
    }

    /**
     * Invokes an interactive authentication process using the specified
     * Authentication Provider
     *
     * @param provider The provider used for the authentication process
     * @deprecated use {@link #login(MobileServiceAuthenticationProvider provider, String uriScheme, int authRequestCode)} instead
     */
    public ListenableFuture<MobileServiceUser> login(String provider) {
        return login(provider, (HashMap<String, String>) null);
    }

    /**
     * Invokes an interactive authentication process using the specified
     * Authentication Provider
     *
     * @param provider   The provider used for the authentication process
     * @param parameters Additional parameters for the authentication process
     */
    private ListenableFuture<MobileServiceUser> login(String provider, HashMap<String, String> parameters) {
        mLoginInProgress = true;

        final SettableFuture<MobileServiceUser> resultFuture = SettableFuture.create();

        ListenableFuture<MobileServiceUser> future = mLoginManager.authenticate(provider, mContext, parameters);

        Futures.addCallback(future, new FutureCallback<MobileServiceUser>() {
            @Override
            public void onFailure(Throwable e) {
                mLoginInProgress = false;

                resultFuture.setException(e);
            }

            @Override
            public void onSuccess(MobileServiceUser user) {
                mCurrentUser = user;
                mLoginInProgress = false;

                resultFuture.set(user);
            }
        });

        return resultFuture;
    }

    /**
     * Invokes an interactive authentication process using the specified
     * Authentication Provider
     *
     * @param provider The provider used for the authentication process
     * @param callback Callback to invoke when the authentication process finishes
     * @deprecated use {@link #login(String provider, String uriScheme, int authRequestCode)} instead
     */
    public void login(String provider, final UserAuthenticationCallback callback) {
        login(provider, (HashMap<String, String>) null, callback);
    }

    /**
     * Invokes an interactive authentication process using the specified
     * Authentication Provider
     *
     * @param provider   The provider used for the authentication process
     * @param parameters Additional parameters for the authentication process
     * @param callback   Callback to invoke when the authentication process finishes
     * @deprecated use {@link #login(String provider, HashMap<String, String> parameters, String uriScheme, int authRequestCode)} instead
     */
    public void login(String provider, HashMap<String, String> parameters, final UserAuthenticationCallback callback) {
        ListenableFuture<MobileServiceUser> loginFuture = login(provider, parameters);

        Futures.addCallback(loginFuture, new FutureCallback<MobileServiceUser>() {
            @Override
            public void onFailure(Throwable exception) {
                if (exception instanceof Exception) {
                    callback.onCompleted(null, (Exception) exception, MobileServiceException.getServiceResponse(exception));
                } else {
                    callback.onCompleted(null, new Exception(exception), MobileServiceException.getServiceResponse(exception));
                }
            }

            @Override
            public void onSuccess(MobileServiceUser user) {
                callback.onCompleted(user, null, null);
            }
        });
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken A Json object representing the oAuth token used for
     *                   authentication
     */
    public ListenableFuture<MobileServiceUser> login(MobileServiceAuthenticationProvider provider, JsonObject oAuthToken) {
        return login(provider.toString(), oAuthToken);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken A Json object representing the oAuth token used for
     * @param parameters Additional parameters for the authentication process
     *                   authentication
     */
    public ListenableFuture<MobileServiceUser> login(MobileServiceAuthenticationProvider provider, JsonObject oAuthToken, HashMap<String, String> parameters) {
        return login(provider.toString(), oAuthToken, parameters);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken A Json object representing the oAuth token used for
     *                   authentication
     * @param callback   Callback to invoke when the authentication process finishes
     * @deprecated use {@link #login( com.microsoft.windowsazure.mobileservices.authentication.MobileServiceAuthenticationProvider provider, com.google.gson.JsonObject oAuthToken)} instead
     */
    public void login(MobileServiceAuthenticationProvider provider, JsonObject oAuthToken, UserAuthenticationCallback callback) {
        login(provider, oAuthToken, null, callback);
    }


    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken A Json object representing the oAuth token used for
     *                   authentication
     * @param parameters Additional parameters for the authentication process
     * @param callback   Callback to invoke when the authentication process finishes
     * @deprecated use {@link #login( com.microsoft.windowsazure.mobileservices.authentication.MobileServiceAuthenticationProvider provider, com.google.gson.JsonObject oAuthToken)} instead
     */
    public void login(MobileServiceAuthenticationProvider provider, JsonObject oAuthToken, HashMap<String, String> parameters, UserAuthenticationCallback callback) {
        login(provider.toString(), oAuthToken, parameters, callback);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken A Json object representing the oAuth token used for
     *                   authentication
     */
    public ListenableFuture<MobileServiceUser> login(String provider, JsonObject oAuthToken) {
        if (oAuthToken == null) {
            throw new IllegalArgumentException("oAuthToken cannot be null");
        }

        return login(provider, oAuthToken.toString());
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken A Json object representing the oAuth token used for
     *                   authentication
     * @param parameters Additional parameters for the authentication process
     */
    public ListenableFuture<MobileServiceUser> login(String provider, JsonObject oAuthToken, HashMap<String, String> parameters) {
        if (oAuthToken == null) {
            throw new IllegalArgumentException("oAuthToken cannot be null");
        }

        return login(provider, oAuthToken.toString(), parameters);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken A Json object representing the oAuth token used for
     *                   authentication
     * @param callback   Callback to invoke when the authentication process finishes
     * @deprecated use {@link #login(String provider, com.google.gson.JsonObject oAuthToken)}
     * instead
     */
    public void login(String provider, JsonObject oAuthToken, UserAuthenticationCallback callback) {
        login(provider, oAuthToken.toString(), null, callback);
    }


    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken A Json object representing the oAuth token used for
     *                   authentication
     * @param parameters Additional parameters for the authentication process
     * @param callback   Callback to invoke when the authentication process finishes
     * @deprecated use {@link #login(String provider, com.google.gson.JsonObject oAuthToken)}
     * instead
     */
    public void login(String provider, JsonObject oAuthToken, HashMap<String, String> parameters, UserAuthenticationCallback callback) {
        if (oAuthToken == null) {
            throw new IllegalArgumentException("oAuthToken cannot be null");
        }

        login(provider, oAuthToken.toString(), parameters, callback);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken The oAuth token used for authentication
     */
    public ListenableFuture<MobileServiceUser> login(MobileServiceAuthenticationProvider provider, String oAuthToken) {
        return login(provider, oAuthToken, (HashMap<String, String>) null);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken The oAuth token used for authentication
     * @param parameters Additional parameters for the authentication process
     */
    public ListenableFuture<MobileServiceUser> login(MobileServiceAuthenticationProvider provider, String oAuthToken, HashMap<String, String> parameters) {
        return login(provider.toString(), oAuthToken, parameters);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken The oAuth token used for authentication
     * @param callback   Callback to invoke when the authentication process finishes
     * @deprecated use {@link #login( com.microsoft.windowsazure.mobileservices.authentication.MobileServiceAuthenticationProvider provider, String oAuthToken)} instead
     */
    public void login(MobileServiceAuthenticationProvider provider, String oAuthToken, UserAuthenticationCallback callback) {
        login(provider, oAuthToken, null, callback);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken The oAuth token used for authentication
     * @param parameters Additional parameters for the authentication process
     * @param callback   Callback to invoke when the authentication process finishes
     * @deprecated use {@link #login( com.microsoft.windowsazure.mobileservices.authentication.MobileServiceAuthenticationProvider provider, String oAuthToken)} instead
     */
    public void login(MobileServiceAuthenticationProvider provider, String oAuthToken, HashMap<String, String> parameters, UserAuthenticationCallback callback) {
        login(provider.toString(), oAuthToken, parameters, callback);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken The oAuth token used for authentication
     */
    public ListenableFuture<MobileServiceUser> login(String provider, String oAuthToken) {
        return login(provider, oAuthToken, (HashMap<String, String>) null);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken The oAuth token used for authentication
     */
    public ListenableFuture<MobileServiceUser> login(String provider, String oAuthToken, HashMap<String, String> parameters) {
        if (oAuthToken == null) {
            throw new IllegalArgumentException("oAuthToken cannot be null");
        }

        final SettableFuture<MobileServiceUser> resultFuture = SettableFuture.create();

        mLoginInProgress = true;

        ListenableFuture<MobileServiceUser> future = mLoginManager.authenticate(provider, oAuthToken, parameters);

        Futures.addCallback(future, new FutureCallback<MobileServiceUser>() {
            @Override
            public void onFailure(Throwable e) {
                mLoginInProgress = false;

                resultFuture.setException(e);
            }

            @Override
            public void onSuccess(MobileServiceUser user) {
                mCurrentUser = user;
                mLoginInProgress = false;

                resultFuture.set(user);
            }
        });

        return resultFuture;
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken The oAuth token used for authentication
     * @param callback   Callback to invoke when the authentication process finishes
     * @deprecated use {@link #login(String provider, String oAuthToken)} instead
     */
    public void login(String provider, String oAuthToken, final UserAuthenticationCallback callback) {
        login(provider, oAuthToken, null, callback);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a
     * provider-specific oAuth token
     *
     * @param provider   The provider used for the authentication process
     * @param oAuthToken The oAuth token used for authentication
     * @param parameters Additional parameters for the authentication process
     * @param callback   Callback to invoke when the authentication process finishes
     * @deprecated use {@link #login(String provider, String oAuthToken)} instead
     */
    public void login(String provider, String oAuthToken, HashMap<String, String> parameters, final UserAuthenticationCallback callback) {
        ListenableFuture<MobileServiceUser> loginFuture = login(provider, oAuthToken, parameters);

        Futures.addCallback(loginFuture, new FutureCallback<MobileServiceUser>() {
            @Override
            public void onFailure(Throwable exception) {
                if (exception instanceof Exception) {
                    callback.onCompleted(null, (Exception) exception, MobileServiceException.getServiceResponse(exception));
                } else {
                    callback.onCompleted(null, new Exception(exception), MobileServiceException.getServiceResponse(exception));
                }
            }

            @Override
            public void onSuccess(MobileServiceUser user) {
                callback.onCompleted(user, null, null);
            }
        });
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a the Google
     * account registered in the device
     *
     * @param activity The activity that triggered the authentication
     */
    public ListenableFuture<MobileServiceUser> loginWithGoogleAccount(Activity activity) {
        return loginWithGoogleAccount(activity, GOOGLE_USER_INFO_SCOPE);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a the Google
     * account registered in the device
     *
     * @param activity The activity that triggered the authentication
     * @param callback Callback to invoke when the authentication process finishes
     * @deprecated use {@link #loginWithGoogleAccount( android.app.Activity activity)} instead
     */
    public void loginWithGoogleAccount(Activity activity, final UserAuthenticationCallback callback) {
        loginWithGoogleAccount(activity, GOOGLE_USER_INFO_SCOPE, callback);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a the Google
     * account registered in the device
     *
     * @param activity The activity that triggered the authentication
     * @param scopes   The scopes used as authentication token type for login
     */
    public ListenableFuture<MobileServiceUser> loginWithGoogleAccount(Activity activity, String scopes) {
        AccountManager acMgr = AccountManager.get(activity.getApplicationContext());
        Account[] accounts = acMgr.getAccountsByType(GOOGLE_ACCOUNT_TYPE);

        Account account;
        if (accounts.length == 0) {
            account = null;
        } else {
            account = accounts[0];
        }

        return loginWithGoogleAccount(activity, account, scopes);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a the Google
     * account registered in the device
     *
     * @param activity The activity that triggered the authentication
     * @param scopes   The scopes used as authentication token type for login
     * @param callback Callback to invoke when the authentication process finishes
     * @deprecated use {@link #loginWithGoogleAccount( android.app.Activity activity, String scopes)} instead
     */
    public void loginWithGoogleAccount(Activity activity, String scopes, final UserAuthenticationCallback callback) {
        ListenableFuture<MobileServiceUser> loginFuture = loginWithGoogleAccount(activity, scopes);

        Futures.addCallback(loginFuture, new FutureCallback<MobileServiceUser>() {
            @Override
            public void onFailure(Throwable exception) {
                if (exception instanceof Exception) {
                    callback.onCompleted(null, (Exception) exception, MobileServiceException.getServiceResponse(exception));
                } else {
                    callback.onCompleted(null, new Exception(exception), MobileServiceException.getServiceResponse(exception));
                }
            }

            @Override
            public void onSuccess(MobileServiceUser user) {
                callback.onCompleted(user, null, null);
            }
        });
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a the Google
     * account registered in the device
     *
     * @param activity The activity that triggered the authentication
     * @param account  The account used for the login operation
     */
    public ListenableFuture<MobileServiceUser> loginWithGoogleAccount(Activity activity, Account account) {
        return loginWithGoogleAccount(activity, account, GOOGLE_USER_INFO_SCOPE);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a the Google
     * account registered in the device
     *
     * @param activity The activity that triggered the authentication
     * @param account  The account used for the login operation
     * @param callback Callback to invoke when the authentication process finishes
     * @deprecated use {@link #loginWithGoogleAccount( android.app.Activity activity, android.accounts.Account account)} instead
     */
    public void loginWithGoogleAccount(Activity activity, Account account, final UserAuthenticationCallback callback) {
        loginWithGoogleAccount(activity, account, GOOGLE_USER_INFO_SCOPE, callback);
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a the Google
     * account registered in the device
     *
     * @param activity The activity that triggered the authentication
     * @param account  The account used for the login operation
     * @param scopes   The scopes used as authentication token type for login
     */
    public ListenableFuture<MobileServiceUser> loginWithGoogleAccount(Activity activity, Account account, String scopes) {
        final SettableFuture<MobileServiceUser> future = SettableFuture.create();

        try {
            if (account == null) {
                throw new IllegalArgumentException("account");
            }

            final MobileServiceClient client = this;

            AccountManagerCallback<Bundle> authCallback = new AccountManagerCallback<Bundle>() {

                @Override
                public void run(AccountManagerFuture<Bundle> futureBundle) {
                    try {
                        if (futureBundle.isCancelled()) {
                            future.setException(new MobileServiceException("User cancelled"));
                            // callback.onCompleted(null, new
                            // MobileServiceException("User cancelled"), null);
                        } else {
                            Bundle bundle = futureBundle.getResult();

                            String token = (String) (bundle.get(AccountManager.KEY_AUTHTOKEN));

                            JsonObject json = new JsonObject();
                            json.addProperty("access_token", token);

                            ListenableFuture<MobileServiceUser> loginFuture = client.login(MobileServiceAuthenticationProvider.Google, json);

                            Futures.addCallback(loginFuture, new FutureCallback<MobileServiceUser>() {
                                @Override
                                public void onFailure(Throwable e) {
                                    future.setException(e);
                                }

                                @Override
                                public void onSuccess(MobileServiceUser user) {
                                    future.set(user);
                                }
                            });
                        }
                    } catch (Exception e) {
                        future.setException(e);
                    }
                }
            };

            AccountManager acMgr = AccountManager.get(activity.getApplicationContext());
            acMgr.getAuthToken(account, scopes, null, activity, authCallback, null);

        } catch (Exception e) {
            future.setException(e);
        }

        return future;
    }

    /**
     * Invokes Microsoft Azure Mobile Service authentication using a the Google
     * account registered in the device
     *
     * @param activity The activity that triggered the authentication
     * @param account  The account used for the login operation
     * @param scopes   The scopes used as authentication token type for login
     * @param callback Callback to invoke when the authentication process finishes
     * @deprecated use {@link #loginWithGoogleAccount( android.app.Activity activity, android.accounts.Account account, String scopes)} instead
     */
    public void loginWithGoogleAccount(Activity activity, Account account, String scopes, final UserAuthenticationCallback callback) {
        ListenableFuture<MobileServiceUser> loginFuture = loginWithGoogleAccount(activity, account, scopes);

        Futures.addCallback(loginFuture, new FutureCallback<MobileServiceUser>() {
            @Override
            public void onFailure(Throwable exception) {
                if (exception instanceof Exception) {
                    callback.onCompleted(null, (Exception) exception, MobileServiceException.getServiceResponse(exception));
                } else {
                    callback.onCompleted(null, new Exception(exception), MobileServiceException.getServiceResponse(exception));
                }
            }

            @Override
            public void onSuccess(MobileServiceUser user) {
                callback.onCompleted(user, null, null);
            }
        });
    }

    /**
     * Log the user out of the Mobile Service
     */
    public ListenableFuture logout() {
        final SettableFuture logoutFuture = SettableFuture.create();

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                mCurrentUser = null;
                logoutFuture.set(null);
                return null;
            }
        }.execute();
        return logoutFuture;
    }

    /**
     * @return The Mobile Service URL
     */
    public URL getAppUrl() {
        return mAppUrl;
    }

    /**
     * Indicates if a login operation is in progress
     */
    public boolean isLoginInProgress() {
        return mLoginInProgress;
    }

    /**
     * @return the current authenticated user
     */
    public MobileServiceUser getCurrentUser() {
        return mCurrentUser;
    }

    /**
     * Sets a user to authenticate the Mobile Service operations
     *
     * @param user The user used to authenticate requests
     */
    public void setCurrentUser(MobileServiceUser user) {
        mCurrentUser = user;
    }

    /**
     * @return a MobileServiceSyncContext instance.
     *
     */
    public MobileServiceSyncContext getSyncContext() {
        return this.mSyncContext;
    }

    /**
     * Creates a MobileServiceJsonTable
     *
     * @param name Table name
     * @return MobileServiceJsonTable with the given name
     */
    public MobileServiceJsonTable getTable(String name) {
        return new MobileServiceJsonTable(name, this);
    }

    /**
     * @return a MobileServiceJsonSyncTable instance, which provides untyped
     * data operations for a local table.
     *
     * @param name Table name
     * @return The MobileServiceJsonSyncTable instance
     */
    public MobileServiceJsonSyncTable getSyncTable(String name) {
        return new MobileServiceJsonSyncTable(name, this);
    }

    /**
     * Creates a MobileServiceTable
     *
     * @param clazz The class used for table name and data serialization
     * @return MobileServiceTable with the given name
     */
    public <E> MobileServiceTable<E> getTable(Class<E> clazz) {
        return this.getTable(clazz.getSimpleName(), clazz);
    }

    /**
     * Creates a MobileServiceTable
     *
     * @param name  Table name
     * @param clazz The class used for data serialization
     * @return MobileServiceTable with the given name
     */
    public <E> MobileServiceTable<E> getTable(String name, Class<E> clazz) {
        validateClass(clazz);
        return new MobileServiceTable<E>(name, this, clazz);
    }

    /**
     * @return a MobileServiceSyncTable<E> instance, which provides strongly
     * typed data operations for a local table.
     *
     * @param clazz The class used for table name and data serialization
     */
    public <E> MobileServiceSyncTable<E> getSyncTable(Class<E> clazz) {
        return this.getSyncTable(clazz.getSimpleName(), clazz);
    }

    /**
     * @return a MobileServiceSyncTable<E> instance, which provides strongly
     * typed data operations for a local table.
     *
     * @param name  Table name
     * @param clazz The class used for data serialization
     */
    public <E> MobileServiceSyncTable<E> getSyncTable(String name, Class<E> clazz) {
        validateClass(clazz);
        return new MobileServiceSyncTable<E>(name, this, clazz);
    }

    /**
     * Invokes a custom API using POST HTTP method
     *
     * @param apiName The API name
     * @param clazz   The API result class
     */
    public <E> ListenableFuture<E> invokeApi(String apiName, Class<E> clazz) {
        return invokeApi(apiName, null, HttpConstants.PostMethod, null, clazz);
    }

    /**
     * Invokes a custom API using POST HTTP method
     *
     * @param apiName  The API name
     * @param clazz    The API result class
     * @param callback The callback to invoke after the API execution
     * @deprecated use {@link #invokeApi(String apiName, Class clazz)} instead
     */
    public <E> void invokeApi(String apiName, Class<E> clazz, ApiOperationCallback<E> callback) {
        invokeApi(apiName, null, HttpConstants.PostMethod, null, clazz, callback);
    }

    /**
     * Invokes a custom API using POST HTTP method
     *
     * @param apiName The API name
     * @param body    The object to send as the request body
     * @param clazz   The API result class
     */
    public <E> ListenableFuture<E> invokeApi(String apiName, Object body, Class<E> clazz) {
        return invokeApi(apiName, body, HttpConstants.PostMethod, null, clazz);
    }

    /**
     * Invokes a custom API using POST HTTP method
     *
     * @param apiName  The API name
     * @param body     The object to send as the request body
     * @param clazz    The API result class
     * @param callback The callback to invoke after the API execution
     * @deprecated use {@link #invokeApi(String apiName, Object body, Class clazz)} instead
     */
    public <E> void invokeApi(String apiName, Object body, Class<E> clazz, ApiOperationCallback<E> callback) {
        invokeApi(apiName, body, HttpConstants.PostMethod, null, clazz, callback);
    }

    /**
     * Invokes a custom API
     *
     * @param apiName    The API name
     * @param httpMethod The HTTP Method used to invoke the API
     * @param parameters The query string parameters sent in the request
     * @param clazz      The API result class
     */
    public <E> ListenableFuture<E> invokeApi(String apiName, String httpMethod, List<Pair<String, String>> parameters, Class<E> clazz) {
        return invokeApi(apiName, null, httpMethod, parameters, clazz);
    }

    /**
     * Invokes a custom API
     *
     * @param apiName    The API name
     * @param httpMethod The HTTP Method used to invoke the API
     * @param parameters The query string parameters sent in the request
     * @param clazz      The API result class
     * @param callback   The callback to invoke after the API execution
     * @deprecated use {@link #invokeApi(String apiName, String httpMethod, List parameters, Class clazz)}
     * instead
     */
    public <E> void invokeApi(String apiName, String httpMethod, List<Pair<String, String>> parameters, Class<E> clazz, ApiOperationCallback<E> callback) {
        invokeApi(apiName, null, httpMethod, parameters, clazz, callback);
    }

    /**
     * Invokes a custom API
     *
     * @param apiName    The API name
     * @param body       The object to send as the request body
     * @param httpMethod The HTTP Method used to invoke the API
     * @param parameters The query string parameters sent in the request
     * @param clazz      The API result class
     */
    public <E> ListenableFuture<E> invokeApi(String apiName, Object body, String httpMethod, List<Pair<String, String>> parameters, final Class<E> clazz) {
        if (clazz == null) {
            throw new IllegalArgumentException("clazz cannot be null");
        }

        JsonElement json = null;
        if (body != null) {
            if (body instanceof JsonElement) {
                json = (JsonElement) body;
            } else {
                json = getGsonBuilder().create().toJsonTree(body);
            }
        }

        final SettableFuture<E> future = SettableFuture.create();
        ListenableFuture<JsonElement> internalFuture = this.invokeApiInternal(apiName, json, httpMethod, parameters, EnumSet.of(MobileServiceFeatures.TypedApiCall));

        Futures.addCallback(internalFuture, new FutureCallback<JsonElement>() {
            @Override
            public void onFailure(Throwable e) {
                future.setException(e);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void onSuccess(JsonElement jsonElement) {
                Class<?> concreteClass = clazz;
                if (clazz.isArray()) {
                    concreteClass = clazz.getComponentType();
                }

                List<?> entities = JsonEntityParser.parseResults(jsonElement, getGsonBuilder().create(), concreteClass);

                if (clazz.isArray()) {
                    E array = (E) Array.newInstance(concreteClass, entities.size());
                    for (int i = 0; i < entities.size(); i++) {
                        Array.set(array, i, entities.get(i));
                    }

                    future.set(array);
                } else {
                    future.set((E) entities.get(0));
                }
            }
        });

        return future;
    }

    /**
     * Invokes a custom API
     *
     * @param apiName    The API name
     * @param body       The object to send as the request body
     * @param httpMethod The HTTP Method used to invoke the API
     * @param parameters The query string parameters sent in the request
     * @param clazz      The API result class
     * @param callback   The callback to invoke after the API execution
     * @deprecated use {@link #invokeApi(String apiName, Object body, String httpMethod, List parameters, Class clazz)} instead
     */
    public <E> void invokeApi(String apiName, Object body, String httpMethod, List<Pair<String, String>> parameters, final Class<E> clazz,
                              final ApiOperationCallback<E> callback) {

        ListenableFuture<E> invokeApiFuture = invokeApi(apiName, body, httpMethod, parameters, clazz);

        Futures.addCallback(invokeApiFuture, new FutureCallback<E>() {
            @Override
            public void onFailure(Throwable exception) {
                if (exception instanceof Exception) {
                    callback.onCompleted(null, (Exception) exception, MobileServiceException.getServiceResponse(exception));
                } else {
                    callback.onCompleted(null, new Exception(exception), MobileServiceException.getServiceResponse(exception));
                }
            }

            @Override
            public void onSuccess(E result) {
                callback.onCompleted(result, null, null);
            }
        });
    }

    /**
     * Invokes a custom API using POST HTTP method
     *
     * @param apiName The API name
     */
    public ListenableFuture<JsonElement> invokeApi(String apiName) {
        return invokeApi(apiName, (JsonElement) null);
    }

    /**
     * Invokes a custom API using POST HTTP method
     *
     * @param apiName  The API name
     * @param callback The callback to invoke after the API execution
     * @deprecated use {@link #invokeApi(String apiName)} instead
     */
    public void invokeApi(String apiName, ApiJsonOperationCallback callback) {
        invokeApi(apiName, null, callback);
    }

    /**
     * Invokes a custom API using POST HTTP method
     *
     * @param apiName The API name
     * @param body    The json element to send as the request body
     */
    public ListenableFuture<JsonElement> invokeApi(String apiName, JsonElement body) {
        return invokeApi(apiName, body, HttpConstants.PostMethod, null);
    }

    /**
     * Invokes a custom API using POST HTTP method
     *
     * @param apiName  The API name
     * @param body     The json element to send as the request body
     * @param callback The callback to invoke after the API execution
     * @deprecated use {@link #invokeApi(String apiName, com.google.gson.JsonElement body)}
     * instead
     */
    public void invokeApi(String apiName, JsonElement body, ApiJsonOperationCallback callback) {
        invokeApi(apiName, body, HttpConstants.PostMethod, null, callback);
    }

    /**
     * Invokes a custom API
     *
     * @param apiName    The API name
     * @param httpMethod The HTTP Method used to invoke the API
     * @param parameters The query string parameters sent in the request
     */
    public ListenableFuture<JsonElement> invokeApi(String apiName, String httpMethod, List<Pair<String, String>> parameters) {
        return invokeApi(apiName, null, httpMethod, parameters);
    }

    /**
     * Invokes a custom API
     *
     * @param apiName    The API name
     * @param httpMethod The HTTP Method used to invoke the API
     * @param parameters The query string parameters sent in the request
     * @param callback   The callback to invoke after the API execution
     * @deprecated use {@link #invokeApi(String apiName, String httpMethod, List parameters)} instead
     */
    public void invokeApi(String apiName, String httpMethod, List<Pair<String, String>> parameters, ApiJsonOperationCallback callback) {
        invokeApi(apiName, null, httpMethod, parameters, callback);
    }

    /**
     * Invokes a custom API
     *
     * @param apiName    The API name
     * @param body       The json element to send as the request body
     * @param httpMethod The HTTP Method used to invoke the API
     * @param parameters The query string parameters sent in the request
     */
    public ListenableFuture<JsonElement> invokeApi(String apiName, JsonElement body, String httpMethod, List<Pair<String, String>> parameters) {
        return this.invokeApiInternal(apiName, body, httpMethod, parameters, EnumSet.of(MobileServiceFeatures.JsonApiCall));
    }

    /**
     * Invokes a custom API
     *
     * @param apiName    The API name
     * @param body       The json element to send as the request body
     * @param httpMethod The HTTP Method used to invoke the API
     * @param parameters The query string parameters sent in the request
     * @param features   The features used in the request
     */
    private ListenableFuture<JsonElement> invokeApiInternal(String apiName, JsonElement body, String httpMethod, List<Pair<String, String>> parameters, EnumSet<MobileServiceFeatures> features) {

        byte[] content = null;
        if (body != null) {
            try {
                content = body.toString().getBytes(UTF8_ENCODING);
            } catch (UnsupportedEncodingException e) {
                throw new IllegalArgumentException(e);
            }
        }

        List<Pair<String, String>> requestHeaders = new ArrayList<Pair<String, String>>();
        if (body != null) {
            requestHeaders.add(new Pair<String, String>(HttpConstants.ContentType, MobileServiceConnection.JSON_CONTENTTYPE));
        }

        if (parameters != null && !parameters.isEmpty()) {
            features.add(MobileServiceFeatures.AdditionalQueryParameters);
        }

        final SettableFuture<JsonElement> future = SettableFuture.create();
        ListenableFuture<ServiceFilterResponse> internalFuture = invokeApiInternal(apiName, content, httpMethod, requestHeaders, parameters, features);

        Futures.addCallback(internalFuture, new FutureCallback<ServiceFilterResponse>() {
            @Override
            public void onFailure(Throwable e) {
                future.setException(e);
            }

            @Override
            public void onSuccess(ServiceFilterResponse response) {

                String content = response.getContent();

                if (content == null) {
                    future.set(null);

                    return;
                }

                JsonElement json = new JsonParser().parse(content);
                future.set(json);
            }
        });

        return future;
    }

    /**
     * Invokes a custom API
     *
     * @param apiName    The API name
     * @param body       The json element to send as the request body
     * @param httpMethod The HTTP Method used to invoke the API
     * @param parameters The query string parameters sent in the request
     * @param callback   The callback to invoke after the API execution
     * @deprecated use {@link #invokeApi(String apiName, com.google.gson.JsonElement body, String httpMethod, List parameters)} instead
     */
    public void invokeApi(String apiName, JsonElement body, String httpMethod, List<Pair<String, String>> parameters, final ApiJsonOperationCallback callback) {

        ListenableFuture<JsonElement> invokeApiFuture = invokeApi(apiName, body, httpMethod, parameters);

        Futures.addCallback(invokeApiFuture, new FutureCallback<JsonElement>() {
            @Override
            public void onFailure(Throwable exception) {
                if (exception instanceof Exception) {
                    callback.onCompleted(null, (Exception) exception, MobileServiceException.getServiceResponse(exception));
                } else {
                    callback.onCompleted(null, new Exception(exception), MobileServiceException.getServiceResponse(exception));
                }
            }

            @Override
            public void onSuccess(JsonElement result) {
                callback.onCompleted(result, null, null);
            }
        });
    }

    /**
     * Invokes a custom API
     *
     * @param apiName        The API name
     * @param content        The byte array to send as the request body
     * @param httpMethod     The HTTP Method used to invoke the API
     * @param requestHeaders The extra headers to send in the request
     * @param parameters     The query string parameters sent in the request
     */
    public ListenableFuture<ServiceFilterResponse> invokeApi(String apiName, byte[] content, String httpMethod, List<Pair<String, String>> requestHeaders,
                                                             List<Pair<String, String>> parameters) {
        return invokeApiInternal(apiName, content, httpMethod, requestHeaders, parameters, EnumSet.of(MobileServiceFeatures.GenericApiCall));
    }

    /**
     * Invokes a custom API
     *
     * @param apiName        The API name
     * @param content        The byte array to send as the request body
     * @param httpMethod     The HTTP Method used to invoke the API
     * @param requestHeaders The extra headers to send in the request
     * @param parameters     The query string parameters sent in the request
     * @param callback       The callback to invoke after the API execution
     */
    public void invokeApi(String apiName, byte[] content, String httpMethod, List<Pair<String, String>> requestHeaders, List<Pair<String, String>> parameters,
                          final ServiceFilterResponseCallback callback) {

        ListenableFuture<ServiceFilterResponse> invokeApiFuture = invokeApi(apiName, content, httpMethod, requestHeaders, parameters);

        Futures.addCallback(invokeApiFuture, new FutureCallback<ServiceFilterResponse>() {
            @Override
            public void onFailure(Throwable exception) {
                if (exception instanceof Exception) {
                    callback.onResponse(MobileServiceException.getServiceResponse(exception), (Exception) exception);
                } else {
                    callback.onResponse(MobileServiceException.getServiceResponse(exception), new Exception(exception));
                }
            }

            @Override
            public void onSuccess(ServiceFilterResponse result) {
                callback.onResponse(result, null);
            }
        });
    }

    /**
     * Invokes a custom API
     *
     * @param apiName        The API name
     * @param content        The byte array to send as the request body
     * @param httpMethod     The HTTP Method used to invoke the API
     * @param requestHeaders The extra headers to send in the request
     * @param parameters     The query string parameters sent in the request
     * @param features       The SDK features used in the request
     */
    private ListenableFuture<ServiceFilterResponse> invokeApiInternal(String apiName, byte[] content, String httpMethod,
                                                                      List<Pair<String, String>> requestHeaders, List<Pair<String, String>> parameters, EnumSet<MobileServiceFeatures> features) {
        final SettableFuture<ServiceFilterResponse> future = SettableFuture.create();

        if (apiName == null || apiName.trim().equals("")) {
            future.setException(new IllegalArgumentException("apiName cannot be null"));
            return future;
        }

        MobileServiceHttpClient httpClient = new MobileServiceHttpClient(this);
        return httpClient.request(CUSTOM_API_URL + apiName, content, httpMethod, requestHeaders, parameters, features);
    }

    /**
     * Validates the class has an id property defined
     *
     * @param clazz
     */
    private <E> void validateClass(Class<E> clazz) {
        if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
            throw new IllegalArgumentException("The class type used for creating a MobileServiceTable must be a concrete class");
        }

        int idPropertyCount = 0;
        for (Field field : clazz.getDeclaredFields()) {
            SerializedName serializedName = field.getAnnotation(SerializedName.class);
            if (serializedName != null) {
                if (serializedName.value().equalsIgnoreCase("id")) {
                    idPropertyCount++;
                }
            } else {
                if (field.getName().equalsIgnoreCase("id")) {
                    idPropertyCount++;
                }
            }
        }

        if (idPropertyCount != 1) {
            throw new IllegalArgumentException("The class representing the MobileServiceTable must have a single id property defined");
        }
    }

    /**
     * Adds a new filter to the MobileServiceClient
     *
     * @param serviceFilter
     * @return MobileServiceClient with filters updated
     */
    public MobileServiceClient withFilter(final ServiceFilter serviceFilter) {
        if (serviceFilter == null) {
            throw new IllegalArgumentException("Invalid ServiceFilter");
        }

        // Generate a new instance of the MobileServiceClient
        MobileServiceClient newClient = new MobileServiceClient(this);

        // If there's no filter, set serviceFilter with the new filter.
        // Otherwise create a composed filter
        if (mServiceFilter == null) {
            newClient.mServiceFilter = serviceFilter;
        } else {
            final ServiceFilter oldServiceFilter = mServiceFilter;
            final ServiceFilter newServiceFilter = serviceFilter;

            newClient.mServiceFilter = new ServiceFilter() {
                // Create a filter that after executing the new ServiceFilter
                // executes the existing filter
                ServiceFilter externalServiceFilter = newServiceFilter;
                ServiceFilter internalServiceFilter = oldServiceFilter;

                @Override
                public ListenableFuture<ServiceFilterResponse> handleRequest(ServiceFilterRequest request,
                                                                             final NextServiceFilterCallback nextServiceFilterCallback) {

                    // Executes new ServiceFilter
                    return externalServiceFilter.handleRequest(request, new NextServiceFilterCallback() {

                        @Override
                        public ListenableFuture<ServiceFilterResponse> onNext(ServiceFilterRequest request) {
                            // Execute existing ServiceFilter
                            return internalServiceFilter.handleRequest(request, nextServiceFilterCallback);
                        }
                    });

                }
            };
        }

        return newClient;
    }

    /**
     * Gets the ServiceFilter. If there is no ServiceFilter, it creates and returns the service.
     * @return ServiceFilter The service filter to use with the client.
     */
    public ServiceFilter getServiceFilter() {
        if (mServiceFilter == null) {
            return new ServiceFilter() {

                @Override
                public ListenableFuture<ServiceFilterResponse> handleRequest(ServiceFilterRequest request, NextServiceFilterCallback nextServiceFilterCallback) {
                    return nextServiceFilterCallback.onNext(request);
                }
            };
        } else {
            return mServiceFilter;
        }
    }

    /**
     * Creates a MobileServiceConnection
     *
     * @return MobileServiceConnection
     */
    public MobileServiceConnection createConnection() {
        return new MobileServiceConnection(this);
    }

    /**
     * Initializes the MobileServiceClient
     *
     * @param appUrl      Mobile Service URL
     * @param currentUser The Mobile Service user used to authenticate requests
     * @param gsonBuilder the GsonBuilder used to in JSON Serialization/Deserialization
     * @param context     The Context where the MobileServiceClient is created
     */
    private void initialize(URL appUrl, MobileServiceUser currentUser, GsonBuilder gsonBuilder, Context context,
                            OkHttpClientFactory okHttpClientFactory, String loginUriPrefix, URL alternateLoginHost) {
        if (appUrl == null || appUrl.toString().trim().length() == 0) {
            throw new IllegalArgumentException("Invalid Application URL");
        }

        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }

        URL normalizedAppURL = normalizeUrl(appUrl);
        mAppUrl = normalizedAppURL;
        mLoginManager = new LoginManager(this);
        mLoginUriPrefix = loginUriPrefix;
        mAlternateLoginHost = alternateLoginHost;
        mServiceFilter = null;
        mLoginInProgress = false;
        mCurrentUser = currentUser;
        mContext = context;
        mGsonBuilder = gsonBuilder;
        mOkHttpClientFactory = okHttpClientFactory;
        mPush = new MobileServicePush(this, context);
        mSyncContext = new MobileServiceSyncContext(this);
        mCustomTabsLoginManager = new CustomTabsLoginManager(
                mAppUrl != null ? mAppUrl.toString() : null,
                mLoginUriPrefix,
                mAlternateLoginHost != null ? mAlternateLoginHost.toString() : null);
    }

    /**
     * Gets the GsonBuilder used to in JSON Serialization/Deserialization
     */
    public GsonBuilder getGsonBuilder() {
        return mGsonBuilder;
    }

    /**
     * Sets the GsonBuilder used to in JSON Serialization/Deserialization
     *
     * @param gsonBuilder The GsonBuilder to set
     */
    public void setGsonBuilder(GsonBuilder gsonBuilder) {
        mGsonBuilder = gsonBuilder;
    }

    /**
     * Registers a JsonSerializer for the specified type
     *
     * @param type       The type to use in the registration
     * @param serializer The serializer to use in the registration
     */
    public <T> void registerSerializer(Type type, JsonSerializer<T> serializer) {
        mGsonBuilder.registerTypeAdapter(type, serializer);
    }

    /**
     * Registers a JsonDeserializer for the specified type
     *
     * @param type         The type to use in the registration
     * @param deserializer The deserializer to use in the registration
     */
    public <T> void registerDeserializer(Type type, JsonDeserializer<T> deserializer) {
        mGsonBuilder.registerTypeAdapter(type, deserializer);
    }

    /**
     * Gets the Context object used to create the MobileServiceClient
     */
    public Context getContext() {
        return mContext;
    }

    /**
     * Sets the Context object for the MobileServiceClient
     */
    public void setContext(Context mContext) {
        this.mContext = mContext;
    }

    /**
     * Gets the AndroidHttpClientFactory
     *
     * @return OkHttp Client Factory
     */
    public OkHttpClientFactory getOkHttpClientFactory() {
        return mOkHttpClientFactory;
    }

    /**
     * Sets the AndroidHttpClientFactory
     */
    public void setAndroidHttpClientFactory(OkHttpClientFactory mOkHttpClientFactory) {
        this.mOkHttpClientFactory = mOkHttpClientFactory;
    }

    /**
     * Gets the MobileServicePush used for push notifications
     */
    public MobileServicePush getPush() {
        return mPush;
    }
}
