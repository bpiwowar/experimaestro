package net.bpiwowar.xpm.manager.scripting;

import java.util.Map;

/**
 * Arguments of a method call from a scripting language
 */
public class Arguments {
    final public LanguageContext context;
    final Object[] args;
    final Map<String, Object> options;

    public Arguments(LanguageContext context, Object[] args, Map<String, Object> options) {
        this.context = context;
        this.args = args;
        this.options = options;
    }
}