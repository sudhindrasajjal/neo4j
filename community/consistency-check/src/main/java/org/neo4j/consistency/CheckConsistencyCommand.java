/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.consistency;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.neo4j.commandline.admin.AdminCommand;
import org.neo4j.commandline.admin.CommandFailed;
import org.neo4j.commandline.admin.IncorrectUsage;
import org.neo4j.commandline.admin.OutsideWorld;
import org.neo4j.commandline.arguments.Arguments;
import org.neo4j.commandline.arguments.OptionalBooleanArg;
import org.neo4j.commandline.arguments.common.OptionalCanonicalPath;
import org.neo4j.consistency.checking.full.ConsistencyCheckIncompleteException;
import org.neo4j.dbms.DatabaseManagementSystemSettings;
import org.neo4j.helpers.Strings;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.helpers.progress.ProgressMonitorFactory;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.pagecache.ConfigurableStandalonePageCacheFactory;
import org.neo4j.kernel.impl.recovery.RecoveryRequiredChecker;
import org.neo4j.logging.FormattedLogProvider;
import org.neo4j.server.configuration.ConfigLoader;

import static java.lang.String.format;
import static org.neo4j.dbms.DatabaseManagementSystemSettings.database_path;

public class CheckConsistencyCommand implements AdminCommand
{

    private static final Arguments arguments = new Arguments()
            .withDatabase()
            .withArgument( new OptionalCanonicalPath( "backup", "/path/to/backup", "",
                    "Path to backup to check consistency of. Cannot be used together with --database." ) )
            .withAdditionalConfig()
            .withArgument( new OptionalBooleanArg( "verbose", false, "Enable verbose output." ) )
            .withArgument( new OptionalCanonicalPath( "report-dir", "directory", ".",
                    "Directory to write report file in.") );

    private final Path homeDir;
    private final Path configDir;
    private final OutsideWorld outsideWorld;
    private final ConsistencyCheckService consistencyCheckService;

    public CheckConsistencyCommand( Path homeDir, Path configDir, OutsideWorld outsideWorld )
    {
        this( homeDir, configDir, outsideWorld, new ConsistencyCheckService() );
    }

    public CheckConsistencyCommand( Path homeDir, Path configDir, OutsideWorld outsideWorld,
            ConsistencyCheckService consistencyCheckService )
    {
        this.homeDir = homeDir;
        this.configDir = configDir;
        this.outsideWorld = outsideWorld;
        this.consistencyCheckService = consistencyCheckService;
    }

    @Override
    public void execute( String[] args ) throws IncorrectUsage, CommandFailed
    {
        final String database;
        final Boolean verbose;
        final Optional<Path> additionalConfigFile;
        final Path reportDir;
        final Optional<Path> backupPath;

        try
        {
            database = arguments.parse( "database", args );
            backupPath = arguments.parseOptionalPath( "backup", args );
            verbose = arguments.parseBoolean( "verbose", args );
            additionalConfigFile = arguments.parseOptionalPath( "additional-config", args );
            reportDir = arguments.parseOptionalPath( "report-dir", args )
                    .orElseThrow( () -> new IllegalArgumentException( "report-dir must be a valid path" ) );
        }
        catch ( IllegalArgumentException e )
        {
            throw new IncorrectUsage( e.getMessage() );
        }

        if ( backupPath.isPresent() )
        {
            if ( arguments.has( "database", args ) )
            {
                throw new IncorrectUsage( "Only one of '--database' and '--backup' can be specified." );
            }
            if ( !backupPath.get().toFile().isDirectory() )
            {
                throw new CommandFailed( format( "Specified backup should be a directory: %s", backupPath.get() ) );
            }
        }

        Config config = loadNeo4jConfig( homeDir, configDir, database, loadAdditionalConfig( additionalConfigFile ) );

        try ( FileSystemAbstraction fileSystem = new DefaultFileSystemAbstraction() )
        {
            File storeDir = backupPath.map( Path::toFile ).orElse( config.get( database_path ) );
            checkDbState( storeDir, config );
            ConsistencyCheckService.Result consistencyCheckResult = consistencyCheckService
                    .runFullConsistencyCheck( storeDir, config, ProgressMonitorFactory.textual( System.err ),
                            FormattedLogProvider.toOutputStream( System.out ), fileSystem, verbose,
                            reportDir.toFile() );

            if ( !consistencyCheckResult.isSuccessful() )
            {
                throw new CommandFailed( format( "Inconsistencies found. See '%s' for details.",
                        consistencyCheckResult.reportFile() ) );
            }
        }
        catch ( ConsistencyCheckIncompleteException | IOException e )
        {
            throw new CommandFailed( "Consistency checking failed." + e.getMessage(), e );
        }
    }

    private Map<String,String> loadAdditionalConfig( Optional<Path> additionalConfigFile )
    {
        if ( additionalConfigFile.isPresent() )
        {
            try
            {
                return MapUtil.load( additionalConfigFile.get().toFile() );
            }
            catch ( IOException e )
            {
                throw new IllegalArgumentException(
                        String.format( "Could not read configuration file [%s]", additionalConfigFile ), e );
            }
        }

        return new HashMap<>();
    }

    private void checkDbState( File storeDir, Config additionalConfiguration ) throws CommandFailed
    {
        try ( FileSystemAbstraction fileSystem = new DefaultFileSystemAbstraction();
              PageCache pageCache = ConfigurableStandalonePageCacheFactory
                      .createPageCache( fileSystem, additionalConfiguration ) )
        {
            if ( new RecoveryRequiredChecker( fileSystem, pageCache ).isRecoveryRequiredAt( storeDir ) )
            {
                throw new CommandFailed(
                        Strings.joinAsLines( "Active logical log detected, this might be a source of inconsistencies.",
                                "Please recover database before running the consistency check.",
                                "To perform recovery please start database and perform clean shutdown." ) );
            }
        }
        catch ( IOException e )
        {
            outsideWorld.stdErrLine(
                    "Failure when checking for recovery state: '%s', continuing as normal.%n" + e.getMessage() );
        }
    }

    private static Config loadNeo4jConfig( Path homeDir, Path configDir, String databaseName,
            Map<String,String> additionalConfig )
    {
        Config config = ConfigLoader.loadConfigWithConnectorsDisabled( Optional.of( homeDir.toFile() ),
                Optional.of( configDir.resolve( "neo4j.conf" ).toFile() ) );
        additionalConfig.put( DatabaseManagementSystemSettings.active_database.name(), databaseName );
        return config.with( additionalConfig );
    }

    public static Arguments arguments()
    {
        return arguments;
    }
}
