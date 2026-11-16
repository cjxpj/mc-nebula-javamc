package nebula.neoforge.snippet;

import java.util.Map;

/**
 * 承载一段编译产物的类加载器。
 *
 * <p>每个片段（每次源码变化）一个实例：父加载器是宿主自己的类加载器，所以片段能看见
 * {@code Ctx} 与整个 Minecraft / NeoForge API；而它自己的类只从 {@link #classes} 里定义，
 * 不会污染别处，也不会和上一次编译的残留混在一起。</p>
 *
 * <p>片段对象被 {@link JavaSnippet} 的缓存持有；插件卸载时缓存条目丢掉，只要没有别处
 * 引用这个片段（事件监听器、实体、任务），这个加载器和它的类就可以被回收。</p>
 */
final class SnippetClassLoader extends ClassLoader {
    private final Map<String, byte[]> classes;

    SnippetClassLoader(ClassLoader parent, Map<String, byte[]> classes) {
        super(parent);
        this.classes = classes;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classes.get(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }
}
