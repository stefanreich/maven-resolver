package org.eclipse.aether.internal.impl;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.Collection;

import javax.inject.Named;
import javax.inject.Singleton;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.SyncContext;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.impl.SyncContextFactory;
import org.eclipse.aether.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A factory to create synchronization contexts. This default implementation actually does not provide any real
 * synchronization but merely completes the repository system.
 */
@Named
@Singleton
public class DefaultSyncContextFactory
    implements SyncContextFactory
{

    private static final Logger LOGGER = LoggerFactory.getLogger( DefaultSyncContextFactory.class );

    public DefaultSyncContextFactory()
    {
        LOGGER.trace( "TCCL: {}", Thread.currentThread().getContextClassLoader() );
        LOGGER.trace( "CCL: {}", getClass().getClassLoader() );
    }

    public SyncContext newInstance( RepositorySystemSession session, boolean shared )
    {
        LOGGER.trace( "Instance: {}", this );
        return new DefaultSyncContext();
    }

    static class DefaultSyncContext
        implements SyncContext
    {

        public void acquire( Collection<? extends Artifact> artifact, Collection<? extends Metadata> metadata )
        {
        }

        public void close()
        {
        }

    }

}
