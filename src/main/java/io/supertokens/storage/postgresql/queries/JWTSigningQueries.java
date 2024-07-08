/*
 *    Copyright (c) 2021, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.storage.postgresql.queries;

import io.supertokens.pluginInterface.RowMapper;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.jwt.JWTAsymmetricSigningKeyInfo;
import io.supertokens.pluginInterface.jwt.JWTSigningKeyInfo;
import io.supertokens.pluginInterface.jwt.JWTSymmetricSigningKeyInfo;
import io.supertokens.pluginInterface.multitenancy.AppIdentifier;
import io.supertokens.storage.postgresql.Start;
import io.supertokens.storage.postgresql.config.Config;
import io.supertokens.storage.postgresql.utils.Utils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static io.supertokens.storage.postgresql.PreparedStatementValueSetter.NO_OP_SETTER;
import static io.supertokens.storage.postgresql.QueryExecutorTemplate.execute;
import static io.supertokens.storage.postgresql.QueryExecutorTemplate.update;
import static io.supertokens.storage.postgresql.config.Config.getConfig;

public class JWTSigningQueries {
    static String getQueryToCreateJWTSigningTable(Start start) {
        /*
         * created_at should only be used to determine the key that was added to the database last, it should not be
         * used to determine the validity or lifetime of a key. While the assumption that created_at refers to the time
         * the key was generated holds true for keys generated by the core, it is not guaranteed when we allow user
         * defined
         * keys in the future.
         */
        String schema = Config.getConfig(start).getTableSchema();
        String jwtSigningKeysTable = Config.getConfig(start).getJWTSigningKeysTable();
        // @formatter:off
        return "CREATE TABLE IF NOT EXISTS " + jwtSigningKeysTable + " ("
                + "app_id VARCHAR(64) DEFAULT 'public',"
                + "key_id VARCHAR(255) NOT NULL,"
                + "key_string TEXT NOT NULL,"
                + "algorithm VARCHAR(10) NOT NULL,"
                + "created_at BIGINT,"
                + "CONSTRAINT " + Utils.getConstraintName(schema, jwtSigningKeysTable, null, "pkey")
                + " PRIMARY KEY(app_id, key_id),"
                + "CONSTRAINT " + Utils.getConstraintName(schema, jwtSigningKeysTable, "app_id", "fkey")
                + " FOREIGN KEY(app_id)"
                + " REFERENCES " + Config.getConfig(start).getAppsTable() + " (app_id) ON DELETE CASCADE"
                + ");";
        // @formatter:on
    }

    public static String getQueryToCreateAppIdIndexForJWTSigningTable(Start start) {
        return "CREATE INDEX IF NOT EXISTS jwt_signing_keys_app_id_index ON "
                + getConfig(start).getJWTSigningKeysTable() + " (app_id);";
    }

    public static List<JWTSigningKeyInfo> getJWTSigningKeys_Transaction(Start start, Connection con,
                                                                        AppIdentifier appIdentifier)
            throws SQLException, StorageQueryException {
        String QUERY = "SELECT * FROM " + getConfig(start).getJWTSigningKeysTable()
                + " WHERE app_id = ? ORDER BY created_at DESC FOR UPDATE";

        return execute(con, QUERY, pst -> pst.setString(1, appIdentifier.getAppId()), result -> {
            List<JWTSigningKeyInfo> keys = new ArrayList<>();

            while (result.next()) {
                keys.add(JWTSigningKeyInfoRowMapper.getInstance().mapOrThrow(result));
            }

            return keys;
        });
    }

    private static class JWTSigningKeyInfoRowMapper implements RowMapper<JWTSigningKeyInfo, ResultSet> {
        private static final JWTSigningKeyInfoRowMapper INSTANCE = new JWTSigningKeyInfoRowMapper();

        private JWTSigningKeyInfoRowMapper() {
        }

        private static JWTSigningKeyInfoRowMapper getInstance() {
            return INSTANCE;
        }

        @Override
        public JWTSigningKeyInfo map(ResultSet result) throws Exception {
            String keyId = result.getString("key_id");
            String keyString = result.getString("key_string");
            long createdAt = result.getLong("created_at");
            String algorithm = result.getString("algorithm");

            if (keyString.contains("|") || keyString.contains(";")) {
                return new JWTAsymmetricSigningKeyInfo(keyId, createdAt, algorithm, keyString);
            } else {
                return new JWTSymmetricSigningKeyInfo(keyId, createdAt, algorithm, keyString);
            }
        }
    }

    public static void setJWTSigningKeyInfo_Transaction(Start start, Connection con, AppIdentifier appIdentifier,
                                                        JWTSigningKeyInfo info)
            throws SQLException, StorageQueryException {

        String QUERY = "INSERT INTO " + getConfig(start).getJWTSigningKeysTable()
                + "(app_id, key_id, key_string, created_at, algorithm) VALUES(?, ?, ?, ?, ?)";

        update(con, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            pst.setString(2, info.keyId);
            pst.setString(3, info.keyString);
            pst.setLong(4, info.createdAtTime);
            pst.setString(5, info.algorithm);
        });
    }
}
