package dev.buildcli.core.utils.docker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

public abstract class DockerHubUtils {
  private static final String API_URL = "https://hub.docker.com/v2";
  private static final HttpClient httpClient = HttpClient.newHttpClient();
  private static final ObjectMapper objectMapper = new ObjectMapper();

  private DockerHubUtils() {
  }

  public record DockerImage(String name, String tag) {
    @Override
    public String toString() {
      return name + ":" + tag;
    }
  }

  public static List<String> getImageTags(String namespace, String repository) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(API_URL + "/repositories/" + namespace + "/" + repository + "/tags"))
        .GET()
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode root = objectMapper.readTree(response.body());

    List<String> tags = new ArrayList<>();
    root.get("results").forEach(result -> {
      tags.add(result.get("name").asText());
    });

    return tags;
  }

  public static List<DockerImage> searchImagesWithTags(String query) {
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(API_URL + "/search/repositories?query=" + query))
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode root = objectMapper.readTree(response.body());

      List<DockerImage> images = new ArrayList<>();
      root.get("results").forEach(result -> {
        String name = result.get("repo_name").asText();
        try {
          String[] parts = name.split("/");
          getImageTags(parts[0], parts[1])
              .stream()
              .map(tag -> new DockerImage(name, tag))
              .forEach(images::add);

        } catch (Exception e) {
          images.add(new DockerImage(name, "latest"));
        }
      });

      return images;
    } catch (Exception e) {
      return List.of();
    }
  }
}
