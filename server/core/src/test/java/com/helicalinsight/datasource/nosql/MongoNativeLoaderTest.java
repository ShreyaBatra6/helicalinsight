package com.helicalinsight.datasource.nosql;

import com.google.gson.JsonObject;
import com.helicalinsight.efw.exceptions.DatabaseConnectionFailedException;
import com.mongodb.MongoClientSettings;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MongoNativeLoaderTest {

    private static JsonObject form(String url, String user, String password) {
        JsonObject form = new JsonObject();
        form.addProperty("driverName", MongoNativeLoader.DRIVER_ID);
        form.addProperty("dataSourceProvider", "noSql");
        form.addProperty("jdbcUrl", url);
        form.addProperty("userName", user);
        form.addProperty("password", password);
        return form;
    }

    @Test
    public void rejectsNonMongoUrl() {
        DatabaseConnectionFailedException e = assertThrows(DatabaseConnectionFailedException.class,
                () -> MongoNativeLoader.buildSettings(form("jdbc:mysql://localhost:3306/db", "", "")));
        assertTrue(e.getMessage().contains("mongodb://"));
    }

    @Test
    public void malformedUrlDoesNotLeakPassword() {
        DatabaseConnectionFailedException e = assertThrows(DatabaseConnectionFailedException.class,
                () -> MongoNativeLoader.buildSettings(form("mongodb://bob:S3cret@localhost:notaport/db", "", "")));
        assertFalse(e.getMessage().contains("S3cret"));
    }

    @Test
    public void requiresDatabaseName() {
        DatabaseConnectionFailedException e = assertThrows(DatabaseConnectionFailedException.class,
                () -> MongoNativeLoader.buildSettings(form("mongodb://localhost:27017/", "", "")));
        assertTrue(e.getMessage().startsWith("Database name is required"));
    }

    @Test
    public void credentialsUseAuthSourceOption() {
        MongoClientSettings settings = MongoNativeLoader.buildSettings(
                form("mongodb://localhost:27017/hi_demo?authSource=admin", "hi_test", "secret"));
        assertEquals("hi_test", settings.getCredential().getUserName());
        assertEquals("admin", settings.getCredential().getSource());
    }

    @Test
    public void credentialsDefaultToConnectionDatabase() {
        MongoClientSettings settings = MongoNativeLoader.buildSettings(
                form("mongodb://localhost:27017/hi_demo", "hi_test", "secret"));
        assertEquals("hi_demo", settings.getCredential().getSource());
    }

    @Test(timeout = 20000)
    public void unreachableServerFailsWithClearMessage() {
        DatabaseConnectionFailedException e = assertThrows(DatabaseConnectionFailedException.class,
                () -> new MongoNativeLoader().testConnection(form("mongodb://127.0.0.1:1/hi_demo", "", "")));
        assertTrue(e.getMessage().startsWith("Could not reach MongoDB"));
    }
}
