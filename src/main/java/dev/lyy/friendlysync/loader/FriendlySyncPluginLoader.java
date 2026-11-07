package dev.lyy.friendlysync.loader;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

/**
 * Paper bootstrap plugin loader that dynamically resolves external dependencies
 * at server startup via MavenLibraryResolver.
 *
 * This dramatically optimizes the plugin JAR size by eliminating shaded binaries
 * (such as native Zstandard binaries, database drivers, and connection pools)
 * while delegating caching and deduplication to the Paper server bootstrap.
 */
public final class FriendlySyncPluginLoader implements PluginLoader {

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();

        // Use official Paper mirror of Maven Central to respect rate limits and terms of service
        resolver.addRepository(new RemoteRepository.Builder(
            "paper-central",
            "default",
            MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR
        ).build());

        resolver.addDependency(new Dependency(new DefaultArtifact("com.zaxxer:HikariCP:7.1.0"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("com.mysql:mysql-connector-j:9.7.0"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("org.mariadb.jdbc:mariadb-java-client:3.5.10"), null));

        resolver.addDependency(new Dependency(new DefaultArtifact("redis.clients:jedis:8.0.2"), null));

        resolver.addDependency(new Dependency(new DefaultArtifact("com.github.luben:zstd-jni:1.5.7-23"), null));

        classpathBuilder.addLibrary(resolver);
    }
}
