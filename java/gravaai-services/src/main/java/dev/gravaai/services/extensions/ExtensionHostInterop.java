package dev.gravaai.services.extensions;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

/**
 * GraalJS interoperability shim for the host objects an extension draws with ({@link
 * RenderHookContext} and the Skija-backed {@code Canvas2D}).
 *
 * <p>GraalJS 25 exposes an exported Java <em>method</em> of a host object to JavaScript, but no
 * longer maps JavaBean accessors to JavaScript properties. {@code ctx.ctx} or
 * {@code ctx.ctx.fillStyle = "red"} — the API {@code EXTENSIONS.md} documents — would therefore
 * read/write {@code undefined}. This shim wraps every argument a render hook receives in a
 * JavaScript {@link Proxy} that forwards property reads to {@code getX()} and property writes to
 * {@code setX()}, recursively (so {@code ctx.ctx} is a wrapped canvas too). Method calls keep
 * working unchanged, and a JavaScript proxy never exposes anything the sandbox would not already
 * expose: the target stays the host object the host injected.
 */
final class ExtensionHostInterop {

	/**
	 * {@code function(hook) -> function(ctx)}, i.e. the registration-time wrapper applied to every
	 * render hook. The inner function wraps the context the compositor passes in before calling
	 * the extension's hook.
	 */
	private static final String SOURCE = """
			(function(hook) {
			  const uppercase = (name) => name.charAt(0).toUpperCase() + name.slice(1);
			  const wrap = (target) => {
			    if (target === null || target === undefined || typeof target !== 'object') {
			      return target;
			    }
			    return new Proxy(target, {
			      get(t, property) {
			        if (typeof property === 'symbol') {
			          try {
			            return t[property];
			          } catch (ignored) {
			            return undefined;
			          }
			        }
			        const direct = t[property];
			        if (typeof direct === 'function') {
			          return function() { return direct.apply(t, arguments); };
			        }
			        if (direct !== undefined) {
			          return wrap(direct);
			        }
			        const name = String(property);
			        const getter = t['get' + uppercase(name)];
			        if (typeof getter === 'function') {
			          return wrap(getter.call(t));
			        }
			        return undefined;
			      },
			      set(t, property, value) {
			        if (typeof property === 'symbol') {
			          return false;
			        }
			        const name = String(property);
			        const setter = t['set' + uppercase(name)];
			        if (typeof setter === 'function') {
			          setter.call(t, value);
			          return true;
			        }
			        try {
			          t[name] = value;
			        } catch (ignored) {
			          // Read-only host member: like the browser canvas, ignore silently.
			        }
			        return true;
			      }
			    });
			  };
			  return function(ctx) { return hook(wrap(ctx)); };
			})
			""";

	private final Value wrapHook;

	private ExtensionHostInterop(Value wrapHook) {
		this.wrapHook = wrapHook;
	}

	/** Evaluates the shim in one extension's context; failures are manifest errors. */
	static ExtensionHostInterop install(Context context) {
		try {
			return new ExtensionHostInterop(context.eval("js", SOURCE));
		} catch (PolyglotException error) {
			throw new ExtensionManifestException(
					"Could not install the extension host interop shim: " + error.getMessage(), error);
		}
	}

	/** Wraps one registered hook so its {@code ctx} argument exposes Canvas 2D-style properties. */
	Value wrapHook(Value hook) {
		return wrapHook.execute(hook);
	}
}
