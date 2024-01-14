/*******************************************************************************
 * Copyright (c) 2016, 2019 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.adapter.auth.device.x509;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import org.eclipse.hono.test.VertxTools;
import org.eclipse.hono.util.RevocableTrustAnchor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Vertx;
import io.vertx.core.net.SelfSignedCertificate;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Integrations tests verifying the OCSP settings of {@link DeviceCertificateValidator}. The code is
 * tested against running instance of OCSP responder which is set up using openssl tool running in Testcontainers.
 *
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
class OCSPIntegrationTest {
    private static final Logger LOG = LoggerFactory.getLogger(OCSPIntegrationTest.class);

    private static final String OCSP_RESPONDER_URI = "http://127.0.0.1";

    private static final int OCSP_RESPONDER_PORT = 8080;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Container
    private static final GenericContainer OCSP_RESPONDER_CONTAINER = new GenericContainer(
        new ImageFromDockerfile()
                // index.txt database persist information about certificate revocation based on serial number
                .withFileFromClasspath("index.txt", "ocsp/index.txt")
                .withFileFromClasspath("cacert.pem", "ocsp/cacert.pem")
                .withFileFromClasspath("cakey.pem", "ocsp/cakey.pem")
                .withDockerfileFromBuilder(builder ->
                        builder.from("alpine:3.19")
                                .workDir("/ocsp")
                                .copy("index.txt", "/ocsp/index.txt")
                                .copy("cacert.pem", "/ocsp/cacert.pem")
                                .copy("cakey.pem", "/ocsp/cakey.pem")
                                .run("apk add openssl")
                                .cmd("openssl", "ocsp", "-index", "index.txt", "-port",
                                        Integer.toString(OCSP_RESPONDER_PORT), "-rsigner", "cacert.pem", "-rkey",
                                        "cakey.pem", "-CA", "cacert.pem", "-text", "-ignore_err")
                                .expose(OCSP_RESPONDER_PORT)
                                .build()))
                .withExposedPorts(OCSP_RESPONDER_PORT)
                .withLogConsumer(new Slf4jLogConsumer(LOG))
                .waitingFor(Wait.forHttp("/").forPort(OCSP_RESPONDER_PORT));;

    private DeviceCertificateValidator validator;

    /**
     */
    @BeforeEach
    void setUp() {
        validator = new DeviceCertificateValidator();
    }

    /**
     * Verifies that the validator succeeds to verify a valid certificate when OCSP
     * revocation check is disabled.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    void testValidateSucceedsForValidCertWhenOCSPCheckDisabled(final VertxTestContext ctx) throws CertificateException,
        IOException {
        final RevocableTrustAnchor anchor = createTrustAnchor();
        final X509Certificate validCert = loadCertificate("ocsp/ocsp-valid.pem");

        validator.validate(List.of(validCert), anchor).onComplete(ctx.succeedingThenComplete());
    }

    /**
     * Verifies that the validator succeeds to verify a revoked certificate when OCSP
     * revocation check is disabled.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    void testValidateSucceedsForRevokedCertWhenOCSPCheckDisabled(final VertxTestContext ctx)
        throws CertificateException, IOException {
        final RevocableTrustAnchor anchor = createTrustAnchor();
        final X509Certificate revokedCert = loadCertificate("ocsp/ocsp-revoked.pem");

        validator.validate(List.of(revokedCert), anchor).onComplete(ctx.succeedingThenComplete());
    }

    /**
     * Verifies that the validator succeeds to verify a valid certificate when OCSP
     * revocation check is enabled.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    void testValidateSucceedsForValidOCSPCertificate(final VertxTestContext ctx) throws CertificateException, IOException {
        final RevocableTrustAnchor anchor = createTrustAnchor();
        final X509Certificate caCert = loadCertificate("ocsp/cacert.pem");
        anchor.setOcspEnabled(true);
        anchor.setOcspResponderUri(getOCSPResponderUri());
        anchor.setOcspResponderCert(caCert);
        anchor.setOcspNonceEnabled(true);
        final X509Certificate validCert = loadCertificate("ocsp/ocsp-valid.pem");

        validator.validate(List.of(validCert), anchor).onComplete(ctx.succeedingThenComplete());
    }

    /**
     * Verifies that the validator succeeds to verify a valid certificate when OCSP
     * revocation check is enabled and another trust anchor without OCSP is configured.
     *
     * @param vertx The vert.x instance to use.
     * @param ctx The vert.x test context.
     */
    @Test
    void testValidateSucceedsWithAnotherAnchorWithoutOCSP(final Vertx vertx, final VertxTestContext ctx) throws CertificateException, IOException {
        final RevocableTrustAnchor anchor = createTrustAnchor();
        final X509Certificate caCert = loadCertificate("ocsp/cacert.pem");
        anchor.setOcspEnabled(true);
        anchor.setOcspResponderUri(getOCSPResponderUri());
        anchor.setOcspResponderCert(caCert);
        anchor.setOcspNonceEnabled(true);
        final X509Certificate validCert = loadCertificate("ocsp/ocsp-valid.pem");

        final SelfSignedCertificate anotherCaCert = SelfSignedCertificate.create("example.com");
        VertxTools.getCertificate(vertx, anotherCaCert.certificatePath())
            .compose((cert) -> {
                final TrustAnchor anotherAnchor = new RevocableTrustAnchor(cert.getSubjectX500Principal(),
                    cert.getPublicKey(), null);
                return validator.validate(List.of(validCert), Set.of(anotherAnchor, anchor));
            })
            .onComplete(ctx.succeedingThenComplete());
    }

    /**
     * Verifies that untrusted certificate fails to verify also in case that OCSP is enabled.
     *
     * @param vertx The vert.x instance to use.
     * @param ctx The vert.x test context.
     */
    @Test
    void testValidateSucceedsForTrustAnchorBasedOnPublicKey(final Vertx vertx, final VertxTestContext ctx) throws CertificateException, IOException {
        final RevocableTrustAnchor anchor = createTrustAnchor();
        final X509Certificate caCert = loadCertificate("ocsp/cacert.pem");
        anchor.setOcspEnabled(true);
        anchor.setOcspResponderUri(getOCSPResponderUri());
        anchor.setOcspResponderCert(caCert);
        anchor.setOcspNonceEnabled(true);

        final SelfSignedCertificate deviceCert = SelfSignedCertificate.create("iot.eclipse.org");
        VertxTools.getCertificate(vertx, deviceCert.certificatePath())
                .compose(cert -> {
                    return validator.validate(List.of(cert), anchor);
                })
                .onComplete(ctx.failingThenComplete());
    }

    /**
     * Verifies that the validator fails to verify a revoked certificate when OCSP
     * revocation check is enabled.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    void testValidateFailsWithExceptionForRevokedOCSPCertificate(final VertxTestContext ctx) throws CertificateException, IOException {
        final RevocableTrustAnchor anchor = createTrustAnchor();
        final X509Certificate caCert = loadCertificate("ocsp/cacert.pem");
        anchor.setOcspEnabled(true);
        anchor.setOcspResponderUri(getOCSPResponderUri());
        anchor.setOcspResponderCert(caCert);
        anchor.setOcspNonceEnabled(true);
        final X509Certificate validCert = loadCertificate("ocsp/ocsp-revoked.pem");

        validator.validate(List.of(validCert), anchor).onComplete(ctx.failing(t -> {
            ctx.verify(() -> assertInstanceOf(CertificateException.class, t));
            ctx.completeNow();
        }));
    }

    private RevocableTrustAnchor createTrustAnchor()
            throws CertificateException, IOException {
        try (InputStream certificateStream = this.getClass().getClassLoader()
                .getResourceAsStream("ocsp/cacert.pem")) {
            final CertificateFactory fact = CertificateFactory.getInstance("X.509");
            final X509Certificate cert = (X509Certificate) fact.generateCertificate(certificateStream);
            // When principal is loaded from certificate it is encoded as DER PrintableString but when it is created
            // from string it is encoded as UTF8String internally, this causes inconsistent issuerNameHash in OCSP
            // request, which cannot be handled by OpenSSL responder.
            return new RevocableTrustAnchor(cert.getSubjectX500Principal(), cert.getPublicKey(), null);
        }
    }

    private X509Certificate loadCertificate(final String resourcesPath) throws IOException, CertificateException {
        try (InputStream certificateStream = this.getClass().getClassLoader()
                .getResourceAsStream(resourcesPath)) {
            final CertificateFactory fact = CertificateFactory.getInstance("X.509");
            return (X509Certificate) fact.generateCertificate(certificateStream);
        }
    }

    private URI getOCSPResponderUri() {
        return URI.create(OCSP_RESPONDER_URI + ":" + OCSP_RESPONDER_CONTAINER.getMappedPort(8080));
    }
}
