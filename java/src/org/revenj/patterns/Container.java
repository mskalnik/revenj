package org.revenj.patterns;

import java.lang.reflect.Type;
import java.util.List;

public interface Container extends ServiceLocator, AutoCloseable {

	interface Factory<T> {
		T create(Container scope);
	}

	void registerClass(Type type, Class<?> manifest, boolean singleton);

	void registerInstance(Type type, Object service, boolean handleClose);

	void registerFactory(Type type, Factory<?> factory, boolean singleton);

	default <T> void register(Class<T> manifest, boolean singleton) {
		registerClass(manifest, manifest, singleton);
	}

	default <T> void register(Class<T> manifest) {
		registerClass(manifest, manifest, false);
	}

	default void register(Class<?>... manifests) {
		for (Class<?> it : manifests) {
			registerClass(it, it, false);
		}
	}

	default <TInterface, TService extends TInterface> void register(Class<TService> manifest, Class<TInterface> as, boolean singleton) {
		registerClass(as, manifest, singleton);
	}

	default <T> void register(T service) {
		registerInstance(service.getClass(), service, service instanceof AutoCloseable);
	}

	default <TInterface, TService extends TInterface> void register(TService service, Class<TInterface> as) {
		registerInstance(as, service, service instanceof AutoCloseable);
	}

	default <T> void register(Class<T> manifest, Factory<T> service) {
		registerFactory(manifest, service, false);
	}

	Container createScope();
}
