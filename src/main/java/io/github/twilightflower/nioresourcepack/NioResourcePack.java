package io.github.twilightflower.nioresourcepack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resource.ResourceType;
import net.minecraft.resource.pack.AbstractFileResourcePack;
import net.minecraft.resource.pack.ResourcePack;
import net.minecraft.resource.pack.metadata.ResourceMetadataReader;
import net.minecraft.util.Identifier;

public class NioResourcePack implements ResourcePack {
	private static final Logger LOGGER = LoggerFactory.getLogger("NioResourcePack"); 
	
	private final Map<ResourceType, Path> roots;
	private final Path trueRoot;
	private final Runnable closer;
	private final String name;
	private final Map<ResourceType, Set<String>> namespacesMap = new EnumMap<>(ResourceType.class);
	private NioResourcePack(Map<ResourceType, Path> roots, Runnable closer, String name, Path trueRoot) {
		this.roots = roots;
		this.closer = closer;
		this.name = name;
		this.trueRoot = trueRoot;
		
		for(var entry : roots.entrySet()) {
			var namespaces = new HashSet<String>();
			namespacesMap.put(entry.getKey(), namespaces);
			Path p = entry.getValue();
			if(Files.isDirectory(p)) {
				try {
					Files.list(p).filter(Files::isDirectory).forEach(path -> {
						String namespace = p.relativize(path).toString();
						if(isValidNamespace(namespace)) {
							namespaces.add(namespace);
						} else {
							LOGGER.warn("Ignoring invalid namespace " + namespace);
						}
					});
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
	}
	
	public static NioResourcePack singleType(ResourceType type, Path dir, String name) {
		return singleType(type, dir, name, () -> {});
	}
	
	public static NioResourcePack singleType(ResourceType type, Path dir, String name, Runnable closer) {
		return new NioResourcePack(Collections.singletonMap(type, dir), closer, name, dir);
	}
	
	public static NioResourcePack multiType(Path dir, String name) {
		return multiType(dir, name, () -> {});
	}
	
	public static NioResourcePack multiType(Path dir, String name, Runnable closer) {
		var map = new EnumMap<ResourceType, Path>(ResourceType.class);
		for(var type : ResourceType.values()) {
			map.put(type, dir.resolve(type.getDirectory()));
		}
		return new NioResourcePack(map, closer, name, dir);
	}
	
	@Override
	public void close() {
		closer.run();
	}

	@Override
	public boolean contains(ResourceType type, Identifier id) {
		if(roots.containsKey(type)) {
			return Files.isRegularFile(getPath(type, id));
		} else {
			return false;
		}
	}

	@Override
	public Collection<Identifier> findResources(ResourceType type, String namespace, String startingPath, Predicate<Identifier> pathFilter) {
		if(roots.containsKey(type)) {
			Path path = getPath(type, new Identifier(namespace, startingPath));
			if(Files.isDirectory(path)) {
				var paths = new ArrayList<Identifier>();
				try {
					Files.walkFileTree(path, new FindResourcesFileVisitor(ident -> {
						if(pathFilter.test(ident)) {
							paths.add(ident);
						}
					}, roots.get(type), namespace));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				return paths;
			} else {
				return Collections.emptySet();
			}
		} else {
			return Collections.emptySet();
		}
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Set<String> getNamespaces(ResourceType type) {
		return Collections.unmodifiableSet(namespacesMap.getOrDefault(type, Collections.emptySet()));
	}

	@Override
	public InputStream open(ResourceType type, Identifier id) throws IOException {
		if(roots.containsKey(type)) {
			return Files.newInputStream(getPath(type, id));
		} else {
			throw new IOException(String.format("Pack %s does not support resources of type %s", name, type.getDirectory()));
		}
	}

	@Override
	public InputStream openRoot(String file) throws IOException {
		return Files.newInputStream(trueRoot.resolve(file));
	}

	@Override
	public <T> T parseMetadata(ResourceMetadataReader<T> arg0) throws IOException {
		try(InputStream in = this.openRoot("pack.mcmeta")) {
			return AbstractFileResourcePack.parseMetadata(arg0, in);
		}
	}
	
	private Path getPath(ResourceType type, Identifier resource) {
		return roots.get(type).resolve(resource.getNamespace()).resolve(resource.getPath());
	}
	
	private static boolean isValidNamespace(String namespace) {
		for(int i = 0; i < namespace.length(); i++) {
			char c = namespace.charAt(i);
			if(!isValidNamespaceChar(c)) {
				return false;
			}
		}
		return true;
	}
	
	private static boolean isValidPath(String path) {
		for(int i = 0; i < path.length(); i++) {
			char c = path.charAt(i);
			if(!isValidPathChar(c)) {
				return false;
			}
		}
		return true;
	}
	
	private static boolean isValidNamespaceChar(char c) {
		return c == '-' || c == '_' || c == '.' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
	}
	
	private static boolean isValidPathChar(char c) {
		return isValidNamespaceChar(c) || c == '/';
	}
	
	private static class FindResourcesFileVisitor extends SimpleFileVisitor<Path> {
		final Consumer<Identifier> callback;
		final Path root;
		final Path namespaceRoot;
		final String namespace;
		FindResourcesFileVisitor(Consumer<Identifier> callback, Path root, String namespace) {
			this.callback = callback;
			this.root = root;
			this.namespace = namespace;
			this.namespaceRoot = root.resolve(namespace);
		}
		
		@Override
		public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
			if(isValidPath(dir.getFileName().toString())) {
				return FileVisitResult.CONTINUE;
			} else {
				LOGGER.warn("Resource search skipping directory {} as its name is not a valid identifier path", root.relativize(dir));
				return FileVisitResult.SKIP_SUBTREE;
			}
		}
		
		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
			if(isValidPath(file.getFileName().toString())) {
				String fname = namespaceRoot.relativize(file).toString();
				callback.accept(new Identifier(namespace, fname));
			} else {
				LOGGER.warn("Resource search skipping file {} as its name is not a valid identifier path", root.relativize(file));
			}
			return FileVisitResult.CONTINUE;
		}
	}
}
