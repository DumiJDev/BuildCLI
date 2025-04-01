<<<<<<<< HEAD:core/src/main/java/dev/buildcli/core/utils/markdown/highlighter/LanguageHighlighter.java
package dev.buildcli.core.utils.markdown.highlighter;
========
package dev.buildcli.core.utils.console.markdown;
>>>>>>>> c9d6239d6f1ee7275dd4b568bfa52d3908dd6ec7:core/src/main/java/dev/buildcli/core/utils/console/markdown/LanguageHighlighter.java

/**
 * Interface for language-specific syntax highlighters
 */
public interface LanguageHighlighter {
  /**
   * Apply syntax highlighting to code
   *
   * @param code Code to highlight
   * @return Highlighted code with ANSI color codes
   */
  String highlight(String code);
  String[] keywords();
}
