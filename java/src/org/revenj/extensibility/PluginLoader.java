package org.revenj.extensibility;

import org.revenj.patterns.Container;

import java.io.*;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;

public final class PluginLoader {

	private static final String PREFIX = "META-INF/services/";
	private static final Charset UTF8 = Charset.forName("UTF-8");

	private final ClassLoader loader;

	public PluginLoader(ClassLoader loader) {
		this.loader = loader != null ? loader : ClassLoader.getSystemClassLoader();
	}

	public <T> List<Class<T>> find(Class<T> manifest) throws Exception {
		String fullName = PREFIX + manifest.getName();
		//TODO: release class loader to avoid locking up jars on Windows
		Enumeration<URL> configs = loader.getResources(fullName);
		List<Class<T>> plugins = new ArrayList<>();
		while (configs.hasMoreElements()) {
			URL url = configs.nextElement();
			lookupServices(manifest, url, plugins);
		}
		return plugins;
	}

	public <T> T[] resolve(Container container, Class<T> manifest) throws Exception {
		try (Container scope = container.createScope()) {
			List<Class<T>> manifests = find(manifest);
			for (Class<T> sc : manifests) {
				scope.registerClass(manifest, sc, false);
			}
			return (T[]) scope.resolve((GenericArrayType) () -> manifest);
		}
	}

	private <T> void lookupServices(Class<T> manifest, URL u, List<Class<T>> plugins) throws Exception {
		try (InputStream stream = u.openStream();
			 BufferedReader reader = new BufferedReader(new InputStreamReader(stream, UTF8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				int ci = line.indexOf('#');
				if (ci >= 0) line = line.substring(0, ci);
				line = line.trim();
				int n = line.length();
				if (n != 0) {
					if ((line.indexOf(' ') >= 0) || (line.indexOf('\t') >= 0)) {
						throw new IOException("Invalid configuration for " + manifest + " in " + u);
					}
					int cp = line.codePointAt(0);
					if (!Character.isJavaIdentifierStart(cp)) {
						throw new IOException("Invalid configuration for " + manifest + " in " + u);
					}
					for (int i = Character.charCount(cp); i < n; i += Character.charCount(cp)) {
						cp = line.codePointAt(i);
						if (!Character.isJavaIdentifierPart(cp) && (cp != '.')) {
							throw new IOException("Invalid configuration for " + manifest + " in " + u);
						}
					}
					Class<?> service = Class.forName(line);
					plugins.add((Class<T>) service);
				}
			}
		}
	}
}
