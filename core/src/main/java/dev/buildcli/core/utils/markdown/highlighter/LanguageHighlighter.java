package dev.buildcli.core.utils.markdown.highlighter;

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
