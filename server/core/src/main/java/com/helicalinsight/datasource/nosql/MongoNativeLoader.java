package com.helicalinsight.datasource.nosql;

import com.google.gson.JsonObject;
import com.helicalinsight.datasource.GsonUtility;
import com.helicalinsight.efw.exceptions.DatabaseConnectionFailedException;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Native MongoDB connector. Selected by NoSqlUtils when a NoSql data source uses
 * driverName "com.helicalinsight.nosql.mongodb". Unlike MongoDrillLoader it talks to
 * MongoDB directly and does not need Apache Drill.
 */
@Component(MongoNativeLoader.DRIVER_ID)
@Scope("prototype")
public class MongoNativeLoader extends NoSQLLoader {

    public static final String DRIVER_ID = "com.helicalinsight.nosql.mongodb";
    static final int TIMEOUT_MS = 5000;
    private static final Logger logger = LoggerFactory.getLogger(MongoNativeLoader.class);

    @Override
    public boolean loadToMiddleWare(JsonObject formData) {
        // Nothing to register: no middleware is involved. Validate so bad settings are rejected on save.
        buildSettings(formData);
        return true;
    }

    @Override
    public boolean testConnection(JsonObject formData) {
        MongoClientSettings settings = buildSettings(formData);
        ConnectionString connectionString = parse(formData);
        String database = databaseName(connectionString, formData);
        try (MongoClient client = MongoClients.create(settings)) {
            MongoDatabase db = client.getDatabase(database);
            db.runCommand(new Document("ping", 1));
            db.listCollectionNames().first(); // proves the user can read this database
            return true;
        } catch (MongoSecurityException e) {
            throw fail("Authentication failed. Check the username, password and authSource option.", e);
        } catch (MongoTimeoutException e) {
            throw fail("Could not reach MongoDB at " + String.join(",", connectionString.getHosts())
                    + " within " + TIMEOUT_MS / 1000 + " seconds. Check the host and port, and that the server is running.", e);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == 13) {
                throw fail("The user is not authorized to read database '" + database + "'.", e);
            }
            throw fail("MongoDB rejected the request (error code " + e.getErrorCode() + ").", e);
        } catch (MongoException e) {
            throw fail("Could not connect to MongoDB (" + e.getClass().getSimpleName() + ").", e);
        }
    }

    static MongoClientSettings buildSettings(JsonObject formData) {
        ConnectionString connectionString = parse(formData);
        String database = databaseName(connectionString, formData);
        if (isBlank(database)) {
            throw new DatabaseConnectionFailedException("Database name is required, e.g. mongodb://host:27017/database");
        }
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(b -> b.serverSelectionTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        String user = GsonUtility.optString(formData, "userName");
        if (!isBlank(user) && connectionString.getCredential() == null) {
            String password = GsonUtility.optString(formData, "password");
            builder.credential(MongoCredential.createCredential(user,
                    authSource(connectionString.getConnectionString(), database),
                    password == null ? new char[0] : password.toCharArray()));
        }
        return builder.build();
    }

    private static ConnectionString parse(JsonObject formData) {
        String uri = GsonUtility.optString(formData, "jdbcUrl");
        if (isBlank(uri)) {
            throw new DatabaseConnectionFailedException("MongoDB connection URL is required, e.g. mongodb://host:27017/database");
        }
        uri = uri.trim();
        if (!uri.startsWith("mongodb://") && !uri.startsWith("mongodb+srv://")) {
            throw new DatabaseConnectionFailedException("MongoDB connection URL must start with mongodb:// or mongodb+srv://");
        }
        try {
            return new ConnectionString(uri);
        } catch (IllegalArgumentException | MongoException e) {
            // Do not echo the URL: it may contain a password.
            throw new DatabaseConnectionFailedException(
                    "The MongoDB connection URL is not valid. Expected format: mongodb://host:27017/database?authSource=admin");
        }
    }

    private static String databaseName(ConnectionString connectionString, JsonObject formData) {
        if (!isBlank(connectionString.getDatabase())) {
            return connectionString.getDatabase();
        }
        String database = GsonUtility.optString(formData, "database");
        return isBlank(database) ? GsonUtility.optString(formData, "databaseName") : database;
    }

    /** Same default as MongoDB itself: the authSource option, otherwise the connection database. */
    private static String authSource(String uri, String database) {
        int query = uri.indexOf('?');
        if (query >= 0) {
            for (String option : uri.substring(query + 1).split("&")) {
                if (option.toLowerCase(Locale.ROOT).startsWith("authsource=")) {
                    return option.substring("authsource=".length());
                }
            }
        }
        return database;
    }

    private static DatabaseConnectionFailedException fail(String message, Exception cause) {
        // Log the exception type only; driver messages can contain connection details.
        logger.warn("MongoDB connection test failed: {} ({})", message, cause.getClass().getName());
        return new DatabaseConnectionFailedException(message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
