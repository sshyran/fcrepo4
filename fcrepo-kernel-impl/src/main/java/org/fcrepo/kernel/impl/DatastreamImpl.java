/**
 * Copyright 2014 DuraSpace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.kernel.impl;

import static com.codahale.metrics.MetricRegistry.name;
import static org.fcrepo.kernel.impl.utils.FedoraTypesUtils.isFedoraDatastream;
import static org.fcrepo.kernel.impl.utils.FedoraTypesUtils.isFrozen;
import static org.modeshape.jcr.api.JcrConstants.JCR_CONTENT;
import static org.modeshape.jcr.api.JcrConstants.JCR_DATA;
import static org.modeshape.jcr.api.JcrConstants.JCR_MIME_TYPE;
import static org.modeshape.jcr.api.JcrConstants.NT_RESOURCE;
import static org.slf4j.LoggerFactory.getLogger;

import org.fcrepo.kernel.exception.PathNotFoundRuntimeException;
import org.fcrepo.kernel.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.impl.utils.impl.CacheEntryFactory;
import org.fcrepo.kernel.utils.FixityResult;
import org.fcrepo.metrics.RegistryService;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.fcrepo.kernel.Datastream;
import org.fcrepo.kernel.exception.InvalidChecksumException;
import org.fcrepo.kernel.exception.ResourceTypeException;
import org.fcrepo.kernel.services.policy.StoragePolicyDecisionPoint;
import org.fcrepo.kernel.utils.ContentDigest;
import org.modeshape.jcr.api.Binary;
import org.modeshape.jcr.api.JcrConstants;
import org.modeshape.jcr.api.ValueFactory;
import org.slf4j.Logger;

import com.codahale.metrics.Histogram;

/**
 * Abstraction for a Fedora datastream backed by a JCR node.
 *
 * @author ajs6f
 * @since Feb 21, 2013
 */
public class DatastreamImpl extends FedoraResourceImpl implements Datastream {

    private static final Logger LOGGER = getLogger(DatastreamImpl.class);

    static final Histogram contentSizeHistogram =
            RegistryService.getInstance().getMetrics().histogram(name(DatastreamImpl.class, "content-size"));

    /**
     * The JCR node for this datastream
     *
     * @param n an existing {@link Node}
     * @throws ResourceTypeException if the node existed prior to this call but is not a datastream.
     */
    public DatastreamImpl(final Node n) {
        super(n);

        if (node.isNew()) {
            initializeNewDatastreamProperties();
        } else if (!hasMixin(node) && !isFrozen.apply(n)) {
            throw new ResourceTypeException("Attempting to perform a datastream operation on non-datastream resource!");
        }
    }

    /**
     * Create or find a FedoraDatastream at the given path
     *
     * @param session the JCR session to use to retrieve the object
     * @param path the absolute path to the object
     * @param nodeType primary type to assign to node
     */
    public DatastreamImpl(final Session session, final String path, final String nodeType) {
        super(session, path, nodeType);
        if (node.isNew()) {
            initializeNewDatastreamProperties();
        } else if (!hasMixin(node) && !isFrozen.apply(node)) {
            throw new ResourceTypeException("Attempting to perform a datastream operation on non-datastream resource!");
        }
    }

    /**
     * Create or find a FedoraDatastream at the given path
     *
     * @param session the JCR session to use to retrieve the object
     * @param path the absolute path to the object
     * @throws RepositoryException
     */
    public DatastreamImpl(final Session session, final String path) {
        this(session, path, JcrConstants.NT_FILE);
    }

    private void initializeNewDatastreamProperties() {
        try {
            if (node.isNew() || !hasMixin(node)) {
                LOGGER.debug("Setting {} properties on a {} node...",
                        FEDORA_DATASTREAM, JcrConstants.NT_FILE);
                node.addMixin(FEDORA_DATASTREAM);

                if (hasContent()) {
                    decorateContentNode(getContentNode());
                }
            }
        } catch (final RepositoryException ex) {
            LOGGER.warn("Could not decorate {} with {} properties: {}",
                    JCR_CONTENT, FEDORA_DATASTREAM, ex);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.fcrepo.kernel.Datastream#getContent()
     */
    @Override
    public InputStream getContent() {
        try {
            return getBinaryContent().getStream();
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.fcrepo.kernel.Datastream#getBinaryContent()
     */
    @Override
    public javax.jcr.Binary getBinaryContent() {
        try {
            return getContentNode().getProperty(JCR_DATA).getBinary();
        } catch (final PathNotFoundException e) {
            throw new PathNotFoundRuntimeException(e);
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.fcrepo.kernel.Datastream#getContent()
     */
    @Override
    public Node getContentNode() {
        LOGGER.trace("Retrieved datastream content node.");
        try {
            return node.getNode(JCR_CONTENT);
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.fcrepo.kernel.Datastream#getContent()
     */
    @Override
    public boolean hasContent() {
        try {
            return node.hasNode(JCR_CONTENT);
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.fcrepo.kernel.Datastream#setContent(java.io.InputStream,
     * java.lang.String, java.net.URI, java.lang.String,
     * org.fcrepo.kernel.services.policy.StoragePolicyDecisionPoint)
     */
    @Override
    public void setContent(final InputStream content, final String contentType,
            final URI checksum, final String originalFileName,
            final StoragePolicyDecisionPoint storagePolicyDecisionPoint)
        throws InvalidChecksumException {

        try {
            final Node contentNode = findOrCreateChild(node, JCR_CONTENT, NT_RESOURCE);

            if (contentNode.canAddMixin(FEDORA_BINARY)) {
                contentNode.addMixin(FEDORA_BINARY);
            }

            if (contentType != null) {
                contentNode.setProperty(JCR_MIME_TYPE, contentType);
            }

            if (originalFileName != null) {
                contentNode.setProperty(PREMIS_FILE_NAME, originalFileName);
            }

            LOGGER.debug("Created content node at path: {}", contentNode.getPath());

            String hint = null;

            if (storagePolicyDecisionPoint != null) {
                hint = storagePolicyDecisionPoint.evaluatePolicies(node);
            }
            final ValueFactory modevf =
                    (ValueFactory) node.getSession().getValueFactory();
            final Binary binary = modevf.createBinary(content, hint);

        /*
         * This next line of code deserves explanation. If we chose for the
         * simpler line: Property dataProperty =
         * contentNode.setProperty(JCR_DATA, requestBodyStream); then the JCR
         * would not block on the stream's completion, and we would return to
         * the requester before the mutation to the repo had actually completed.
         * So instead we use createBinary(requestBodyStream), because its
         * contract specifies: "The passed InputStream is closed before this
         * method returns either normally or because of an exception." which
         * lets us block and not return until the job is done! The simpler code
         * may still be useful to us for an asynchronous method that we develop
         * later.
         */
            final Property dataProperty = contentNode.setProperty(JCR_DATA, binary);

            final String dsChecksum = binary.getHexHash();
            final URI uriChecksumString = ContentDigest.asURI("SHA-1", dsChecksum);
            if (checksum != null &&
                    !checksum.equals(uriChecksumString)) {
                LOGGER.debug("Failed checksum test");
                throw new InvalidChecksumException("Checksum Mismatch of " +
                        uriChecksumString + " and " + checksum);
            }

            decorateContentNode(contentNode);

            LOGGER.debug("Created data property at path: {}", dataProperty.getPath());

        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.fcrepo.kernel.Datastream#getContentSize()
     */
    @Override
    public long getContentSize() {
        try {
            return getContentNode().getProperty(CONTENT_SIZE)
                    .getLong();
        } catch (final RepositoryException e) {
            LOGGER.info("Could not get contentSize(): {}", e.getMessage());
        }

        return -1L;
    }

    /*
     * (non-Javadoc)
     * @see org.fcrepo.kernel.Datastream#getContentDigest()
     */
    @Override
    public URI getContentDigest() {
        try {
            return new URI(getContentNode().getProperty(CONTENT_DIGEST).getString());
        } catch (final RepositoryException | URISyntaxException e) {
            LOGGER.info("Could not get content digest: {}", e.getMessage());
        }

        return ContentDigest.missingChecksum();
    }

    /*
     * (non-Javadoc)
     * @see org.fcrepo.kernel.Datastream#getMimeType()
     */
    @Override
    public String getMimeType() {
        try {
            if (hasContent() && getContentNode().hasProperty(JCR_MIME_TYPE)) {
                return getContentNode().getProperty(JCR_MIME_TYPE).getString();
            } else {
                return "application/octet-stream";
            }
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.fcrepo.kernel.Datastream#getFilename()
     */
    @Override
    public String getFilename() {
        try {
            if (hasContent() && getContentNode().hasProperty(PREMIS_FILE_NAME)) {
                return getContentNode().getProperty(PREMIS_FILE_NAME).getString();
            }
            return node.getName();
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }
    }

    /**
     * Get the fixity results for this datastream's bitstream, and compare it
     * against the given checksum and size.
     *
     * @param repo
     * @param dsChecksum -the checksum and algorithm represented as a URI
     * @param dsSize
     * @return fixity results
     * @throws RepositoryException
     */
    @Override
    public Collection<FixityResult> getFixity(final Repository repo,
                                              final URI dsChecksum,
                                              final long dsSize) {
        try {
            LOGGER.debug("Checking resource: " + getPath());

            return CacheEntryFactory.forProperty(repo, getContentNode().getProperty(JCR_DATA)).checkFixity(dsChecksum, dsSize);
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }

    }

    private static void decorateContentNode(final Node contentNode) throws RepositoryException {
        if (contentNode == null) {
            LOGGER.warn("{} node appears to be null!", JCR_CONTENT);
            return;
        }
        if (contentNode.canAddMixin(FEDORA_BINARY)) {
            contentNode.addMixin(FEDORA_BINARY);
        }

        final Property dataProperty = contentNode.getProperty(JCR_DATA);
        final Binary binary = (Binary) dataProperty.getBinary();
        final String dsChecksum = binary.getHexHash();

        contentSizeHistogram.update(dataProperty.getLength());

        contentNode.setProperty(CONTENT_SIZE, dataProperty.getLength());
        contentNode.setProperty(CONTENT_DIGEST, ContentDigest.asURI("SHA-1", dsChecksum).toString());

        LOGGER.debug("Decorated data property at path: {}", dataProperty.getPath());
    }

    /**
     * Check if the node has a fedora:datastream mixin
     *
     * @param node node to check
     */
    public static boolean hasMixin(final Node node) {
        return isFedoraDatastream.apply(node);
    }

}
