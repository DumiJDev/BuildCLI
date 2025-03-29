package dev.buildcli.core.actions.ai;

import java.util.Optional;

public interface AIServiceParams {
  Optional<String> model();
  String vendor();
  default Optional<String> token() {
    return Optional.empty();
  }
}
