/*******************************************************************************
 * Copyright (c) 2020, 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.hono.deviceregistry.mongodb.service;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.hono.auth.HonoPasswordEncoder;
import org.eclipse.hono.deviceregistry.mongodb.config.MongoDbBasedCredentialsConfigProperties;
import org.eclipse.hono.deviceregistry.mongodb.model.CredentialsDao;
import org.eclipse.hono.deviceregistry.service.credentials.AbstractCredentialsManagementService;
import org.eclipse.hono.deviceregistry.service.device.DeviceKey;
import org.eclipse.hono.deviceregistry.util.DeviceRegistryUtils;
import org.eclipse.hono.service.management.OperationResult;
import org.eclipse.hono.service.management.credentials.CommonCredential;
import org.eclipse.hono.service.management.credentials.CredentialsDto;
import org.eclipse.hono.tracing.TracingHelper;

import io.opentracing.Span;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

/**
 * A credentials management service that persists data in a MongoDB collection.
 *
 * @see <a href="https://www.eclipse.org/hono/docs/api/management/">Device Registry Management API</a>
 */
public final class MongoDbBasedCredentialsManagementService extends AbstractCredentialsManagementService {

    private final CredentialsDao dao;

    /**
     * Creates a new service for a data access object.
     *
     * @param vertx The vert.x instance to run on.
     * @param dao The data access object to use for accessing data in the MongoDB.
     * @param config The properties for configuring this service.
     * @param passwordEncoder The password encoder.
     * @throws NullPointerException if any of the parameters are {@code null}.
     */
    public MongoDbBasedCredentialsManagementService(
            final Vertx vertx,
            final CredentialsDao dao,
            final MongoDbBasedCredentialsConfigProperties config,
            final HonoPasswordEncoder passwordEncoder) {

        super(Objects.requireNonNull(vertx),
                Objects.requireNonNull(passwordEncoder),
                config.getMaxBcryptCostFactor(),
                config.getHashAlgorithmsWhitelist());

        Objects.requireNonNull(dao);
        Objects.requireNonNull(config);

        this.dao = dao;
    }

    @Override
    protected Future<OperationResult<List<CommonCredential>>> processReadCredentials(
            final DeviceKey deviceKey,
            final Span span) {

        Objects.requireNonNull(deviceKey);
        Objects.requireNonNull(span);

        return dao.getByDeviceId(deviceKey.getTenantId(), deviceKey.getDeviceId(), span.context())
                .map(dto -> OperationResult.ok(
                        HttpURLConnection.HTTP_OK,
                        dto.getCredentials(),
                        Optional.empty(),
                        Optional.of(dto.getVersion())))
                .otherwise(error -> DeviceRegistryUtils.mapErrorToResult(error, span));
    }

    @Override
    protected Future<OperationResult<Void>> processUpdateCredentials(
            final DeviceKey deviceKey,
            final Optional<String> resourceVersion,
            final List<CommonCredential> updatedCredentials,
            final Span span) {

        Objects.requireNonNull(deviceKey);
        Objects.requireNonNull(resourceVersion);
        Objects.requireNonNull(updatedCredentials);
        Objects.requireNonNull(span);

        TracingHelper.TAG_DEVICE_ID.set(span, deviceKey.getDeviceId());

        return tenantInformationService.getTenant(deviceKey.getTenantId(), span)
                .compose(tenant -> tenant.checkCredentialsLimitExceeded(deviceKey.getTenantId(), updatedCredentials))
                .compose(ok -> {
                    final Promise<CredentialsDto> result = Promise.promise();
                    final var updatedCredentialsDto = CredentialsDto.forUpdate(
                            deviceKey.getTenantId(),
                            deviceKey.getDeviceId(),
                            updatedCredentials,
                            DeviceRegistryUtils.getUniqueIdentifier());

                    if (updatedCredentialsDto.requiresMerging()) {
                        // it is no problem to ignore the resource version here because
                        // further down, when we try to update the credentials document we
                        // check for the resource version to match
                        dao.getByDeviceId(deviceKey.getTenantId(), deviceKey.getDeviceId(), span.context())
                                .map(updatedCredentialsDto::merge)
                                .onComplete(result);
                    } else {
                        // simply replace the existing credentials with the
                        // updated ones provided by the client
                        result.complete(updatedCredentialsDto);
                    }
                    return result.future();
                })
                .compose(credentialsDto -> {
                    credentialsDto.createMissingSecretIds();
                    return dao.update(
                            credentialsDto,
                            resourceVersion,
                            span.context());
                })
                .map(newVersion -> OperationResult.ok(
                        HttpURLConnection.HTTP_NO_CONTENT,
                        (Void) null,
                        Optional.empty(),
                        Optional.of(newVersion)))
                .otherwise(error -> DeviceRegistryUtils.mapErrorToResult(error, span));
    }
}
