package nebula.neoforge.snippet;

/**
 * {@code $java.eval} 编译产物的入口约定。
 *
 * <p>词库里的 Java 片段有两种写法：</p>
 * <ul>
 *   <li><b>函数体</b>：只写方法体，宿主补全成 {@code public String run(Ctx, String)}；</li>
 *   <li><b>完整类</b>：自己写 {@code public class X implements NebulaSnippet}，
 *       这时可以有字段，状态跨调用保留。</li>
 * </ul>
 *
 * <p>返回值会原样回填给词库（null 当空串）。片段跑在服务端主线程，可以抛异常，
 * 宿主捕获后记日志并把 {@code [java] 前缀的错误文本} 返回给词库，不会中断词库求值。</p>
 */
public interface NebulaSnippet {
    String run(Ctx ctx, String arg) throws Exception;
}
