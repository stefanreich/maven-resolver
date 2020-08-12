package org.eclipse.aether.synccontext;

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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeSet;

import javax.annotation.Priority;
import javax.inject.Named;
import javax.inject.Singleton;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.SyncContext;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.impl.SyncContextFactory;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.util.ChecksumUtils;
import org.eclipse.aether.util.ConfigUtils;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A factory to create synchronization contexts. This implementation uses Redis, more specifically
 * its {@link RReadWriteLock}. It locks -- very fine-grained -- down to an artifact/metadata version
 * if required. <br>
 * This factory is considered experimental and is intended to be used as a singleton.
 */
@Named
@Priority( Integer.MAX_VALUE )
@Singleton
public class RedissonSyncContextFactory
    implements SyncContextFactory
{

    private static final String DEFAULT_CONFIG_FILE_NAME = "maven-resolver-redisson.yaml";
    private static final String DEFAULT_REDIS_ADDRESS = "redis://localhost:6379";
    private static final String DEFAULT_CLIENT_NAME = "maven-resolver";
    private static final String DEFAULT_DISCRIMINATOR = "default";
    private static final String DEFAULT_DISCRIMINATOR_DIGEST = "2923f6fa36614586ea09b4424b438915cc1b9b67";

    private static final String CONFIG_PROP_CONFIG_FILE = "aether.syncContext.redisson.configFile";

    private static final Logger LOGGER = LoggerFactory.getLogger( RedissonSyncContextFactory.class );

    private static RedissonClient redissonClient = createRedissonClient();
    private static String localhostDiscriminator = createLocalhostDiscriminator();

    public RedissonSyncContextFactory()
    {
        LOGGER.trace( "TCCL '{}', id '{}'", Thread.currentThread().getContextClassLoader(),
                      System.identityHashCode( Thread.currentThread().getContextClassLoader() ) );
        LOGGER.trace( "CCL '{}', id '{}'", getClass().getClassLoader(),
                      System.identityHashCode( getClass().getClassLoader() ) );
    }

    private static RedissonClient createRedissonClient()
    {
        Path configFilePath = null;

        String configFile = ConfigUtils.getString( System.getProperties(), null, CONFIG_PROP_CONFIG_FILE );
        if ( configFile != null && !configFile.isEmpty() )
        {
            configFilePath = Paths.get( configFile );
            if ( Files.notExists( configFilePath ) )
            {
                throw new IllegalArgumentException( "The specified Redisson config file does not exist: "
                                                    + configFilePath );
            }
        }

        if ( configFilePath == null )
        {
            String mavenConf = ConfigUtils.getString( System.getProperties(), null, "maven.conf" );
            if ( mavenConf != null && !mavenConf.isEmpty() )
            {
                configFilePath = Paths.get( mavenConf, DEFAULT_CONFIG_FILE_NAME );
                if ( Files.notExists( configFilePath ) )
                {
                    configFilePath = null;
                }
            }
        }

        Config config = null;

        if ( configFilePath != null )
        {
            LOGGER.trace( "Reading Redisson config file from '{}'", configFilePath );
            try ( InputStream is = Files.newInputStream( configFilePath ) )
            {
                config = Config.fromYAML( is );
            }
            catch ( IOException e )
            {
                throw new IllegalStateException( "Failed to read Redisson config file: " + configFilePath, e );
            }
        }
        else
        {
            config = new Config();
            config.useSingleServer()
                .setAddress( DEFAULT_REDIS_ADDRESS )
                .setClientName( DEFAULT_CLIENT_NAME );
        }

        RedissonClient redissonClient = Redisson.create( config );
        LOGGER.trace( "Created Redisson client with id '{}'", redissonClient.getId() );

        return redissonClient;
    }

    private static String createLocalhostDiscriminator()
    {
        try
        {
            return InetAddress.getLocalHost().getHostName();
        }
        catch ( UnknownHostException e )
        {
            LOGGER.warn( "Failed to calculate localhost descriminator, using '{}'",
                         DEFAULT_DISCRIMINATOR, e );
            return DEFAULT_DISCRIMINATOR;
        }
    }

    public SyncContext newInstance( RepositorySystemSession session, boolean shared )
    {
        LOGGER.trace( "Instance: {}, identity: {}", this, System.identityHashCode( getClass() ) );
        return new RedissonSyncContext( session, localhostDiscriminator, redissonClient, shared );
    }

    static class RedissonSyncContext
        implements SyncContext
    {

        private static final String CONFIG_DISCRIMINATOR = "aether.syncContext.redisson.discriminator";

        private static final String KEY_PREFIX = "maven:resolver:";

        private static final Logger LOGGER = LoggerFactory.getLogger( RedissonSyncContext.class );

        private final RepositorySystemSession session;
        private final String localhostDiscriminator;
        private final RedissonClient redissonClient;
        private final boolean shared;
        private final Map<String, RReadWriteLock> locks = new LinkedHashMap<>();

        private RedissonSyncContext( RepositorySystemSession session, String localhostDiscriminator,
                RedissonClient redisson, boolean shared )
        {
            this.session = session;
            this.localhostDiscriminator = localhostDiscriminator;
            this.redissonClient = redisson;
            this.shared = shared;
        }

        public void acquire( Collection<? extends Artifact> artifacts,
                Collection<? extends Metadata> metadatas )
        {
            // Deadlock prevention: https://stackoverflow.com/a/16780988/696632
            // We must acquire multiple locks always in the same order!
            Collection<String> keys = new TreeSet<>();
            if ( artifacts != null )
            {
                for ( Artifact artifact : artifacts )
                {
                    // TODO Should we include extension and classifier too?
                    String key = "artifact:" + artifact.getGroupId() + ":"
                            + artifact.getArtifactId() + ":" + artifact.getBaseVersion();
                    keys.add( key );
                }
            }

            if ( metadatas != null )
            {
                for ( Metadata metadata : metadatas )
                {
                    String key;
                    if ( !metadata.getGroupId().isEmpty() )
                    {
                        StringBuilder keyBuilder = new StringBuilder( "metadata:" );
                        keyBuilder.append( metadata.getGroupId() );
                        if ( !metadata.getArtifactId().isEmpty() )
                        {
                            keyBuilder.append( ':' ).append( metadata.getArtifactId() );
                            if ( !metadata.getVersion().isEmpty() )
                            {
                                keyBuilder.append( ':' ).append( metadata.getVersion() );
                            }
                        }
                        key = keyBuilder.toString();
                    }
                    else
                    {
                        key = "metadata:ROOT";
                    }
                    keys.add( key );
                }
            }

            if ( keys.isEmpty() )
            {
                return;
            }

            String discriminator = createDiscriminator();
            LOGGER.trace( "Using Redis key discriminator '{}' during this session", discriminator );

            LOGGER.trace( "Need {} {} lock(s) for {}", keys.size(), shared ? "read" : "write", keys );

            int acquiredLockCount = 0;
            int reacquiredLockCount = 0;
            for ( String key : keys )
            {
                RReadWriteLock rwLock = locks.get( key );
                if ( rwLock == null )
                {
                    rwLock = redissonClient
                            .getReadWriteLock( KEY_PREFIX + discriminator + ":" + key );
                    locks.put( key, rwLock );
                    acquiredLockCount++;
                }
                else
                {
                    reacquiredLockCount++;
                }

                RLock actualLock = shared ? rwLock.readLock() : rwLock.writeLock();
                LOGGER.trace( "Acquiring {} lock for '{}' (currently held: {}, already locked: {})",
                              shared ? "read" : "write", key, actualLock.getHoldCount(),
                              actualLock.isLocked() );
                // If this still produces a deadlock we might need to switch to #tryLock() with n attempts
                actualLock.lock();
            }
            LOGGER.trace( "Total new locks acquired: {}, total existing locks reacquired: {}",
                          acquiredLockCount, reacquiredLockCount );
        }

        private String createDiscriminator()
        {
            String discriminator = ConfigUtils.getString( session, null, CONFIG_DISCRIMINATOR );

            if ( discriminator == null || discriminator.isEmpty() )
            {

                File basedir = session.getLocalRepository().getBasedir();
                discriminator = localhostDiscriminator + ":" + basedir;
                try
                {
                    Map<String, Object> checksums = ChecksumUtils.calc(
                            discriminator.toString().getBytes( StandardCharsets.UTF_8 ),
                            Collections.singletonList( "SHA-1" ) );
                    Object checksum = checksums.get( "SHA-1" );

                    if ( checksum instanceof Exception )
                    {
                        throw (Exception) checksum;
                    }

                    return String.valueOf( checksum );
                }
                catch ( Exception e )
                {
                    // TODO Should this be warn?
                    LOGGER.trace( "Failed to calculate discriminator digest, using '{}'",
                                  DEFAULT_DISCRIMINATOR_DIGEST, e );
                    return DEFAULT_DISCRIMINATOR_DIGEST;
                }
            }

            return discriminator;
        }

        public void close()
        {
            if ( locks.isEmpty() )
            {
                return;
            }

            // Release locks in reverse insertion order
            Deque<String> keys = new LinkedList<>( locks.keySet() );
            Iterator<String> keysIter = keys.descendingIterator();
            while ( keysIter.hasNext() )
            {
                String key = keysIter.next();
                RReadWriteLock rwLock = locks.get( key );
                RLock actualLock = shared ? rwLock.readLock() : rwLock.writeLock();
                while ( actualLock.getHoldCount() > 0 )
                {
                    LOGGER.trace( "Releasing {} lock for '{}' (currently held: {})",
                                  shared ? "read" : "write", key, actualLock.getHoldCount() );
                    actualLock.unlock();
                }
            }
            // TODO Should we count reentrant ones too?
            LOGGER.trace( "Total locks released: {}", locks.size() );
            locks.clear();
        }

    }

}
