package com.example.a119_saver;

import android.util.Log;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.GET;
import retrofit2.http.Query;

public class KakaoNavigation {
    private static final String TAG = "KakaoNavigation";
    private static final String TAG2 = "Graph";
    private static final String BASE_URL = "https://apis-navi.kakaomobility.com/";
    private static final String API_KEY = "cb49ad3e6bf961d966a056458a106ea9";
    private final NaviApi naviApi;
    private final double CURRENT_LAT = 37.5873;
    private final double CURRENT_LON = 126.9930;

    //Graph
    private Graph graph;

    public Graph getGraph() {
        return graph;
    }
    //vertex 조회 메서드
    public List<Vertex> getRouteVertices(String from, String to) {
        if (graph == null) return null;
        return graph.getVertexPath(from, to);
    }

    public void printGraphState() {
        // 노드별 정보 출력
        for (String nodeName : graph.getNodes().keySet()) {
            Log.d(TAG, "====== " + nodeName + " ======");

            // hvec 정보 출력 (현재 위치가 아닌 경우만)
            Graph.Node currentNode = graph.getNodes().get(nodeName);
            if (!nodeName.equals("현재 위치") && currentNode.getHvec() != null) {
                Log.d(TAG, "병상 수: " + currentNode.getHvec());
            }

            // 인접 노드와 가중치(시간) 정보 출력
            Map<String, Integer> edges = graph.getAdjacencyMap().get(nodeName);
            Log.d(TAG, "--- 인접 노드까지의 소요 시간 ---");
            for (Map.Entry<String, Integer> edge : edges.entrySet()) {
                String destination = edge.getKey();
                int duration = edge.getValue();
                Log.d(TAG, String.format("-> %s : %d분", destination, duration/60));
            }
            Log.d(TAG, "------------------------");
        }
    }

    public KakaoNavigation() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request()
                            .newBuilder()
                            .addHeader("Authorization", "KakaoAK " + API_KEY)
                            .addHeader("Content-Type", "application/json")
                            .build();
                    return chain.proceed(request);
                })
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        naviApi = retrofit.create(NaviApi.class);
    }

    // API 인터페이스 수정
    public interface NaviApi {
        @GET("v1/directions")
        Call<DirectionsResponse> getDirections(
                @Query("origin") String origin,          // 변경
                @Query("destination") String destination, // 변경
                @Query("priority") String priority
        );
    }

    // 노드 간 경로 정보를 저장할 클래스
    public static class RouteInfo {
        public final String fromName;
        public final String toName;
        public final int distance;  // 미터
        public final int duration;  // 초
        public final List<Vertex> vertices;  // 경로의 모든 좌표점 추가

        public RouteInfo(String fromName, String toName, int distance, int duration, List<Vertex> vertices) {
            this.fromName = fromName;
            this.toName = toName;
            this.distance = distance;
            this.duration = duration;
            this.vertices = vertices;
        }
    }

    // 경로의 각 좌표점을 저장할 클래스
    public static class Vertex {
        public final double lat;
        public final double lon;

        public Vertex(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    // 모든 노드 간 경로 계산을 위한 콜백
    public interface AllRoutesCallback {
        void onSuccess(List<RouteInfo> routes);
        void onError(String message);
    }

    public void calculateAllRoutes(List<MainActivity.Hospital> hospitals, AllRoutesCallback callback) {
        graph = new Graph();
        List<RouteInfo> allRoutes = new ArrayList<>();
        // 현재 위치 노드 추가 (hvec 없음)
        graph.addNode("현재 위치", CURRENT_LAT, CURRENT_LON,99999);

        for (MainActivity.Hospital hospital : hospitals) {
            graph.addNode(
                    hospital.hospital_name,
                    Double.parseDouble(hospital.x),
                    Double.parseDouble(hospital.y),
                    Integer.parseInt(hospital.bed_num)
            );
        }

        List<Point> allPoints = new ArrayList<>();
        allPoints.add(new Point("현재 위치", CURRENT_LAT, CURRENT_LON));
        for (MainActivity.Hospital hospital : hospitals) {
            allPoints.add(new Point(hospital.hospital_name,
                    Double.parseDouble(hospital.x),
                    Double.parseDouble(hospital.y)));
        }

        // API 요청 카운터 추가
        AtomicInteger totalRequests = new AtomicInteger(0);
        AtomicInteger completedRequests = new AtomicInteger(0);

        // 총 요청 수 계산
        for (int i = 0; i < allPoints.size(); i++) {
            for (int j = i + 1; j < allPoints.size(); j++) {
                totalRequests.incrementAndGet();
            }
        }

        // 모든 노드 쌍에 대해 시간 정보 요청
        for (int i = 0; i < allPoints.size(); i++) {
            for (int j = i + 1; j < allPoints.size(); j++) {
                Point origin = allPoints.get(i);
                Point destination = allPoints.get(j);

                Log.d(TAG, String.format("Requesting route from %s(%f,%f) to %s(%f,%f)",
                        origin.name, origin.lat, origin.lon,
                        destination.name, destination.lat, destination.lon));
                naviApi.getDirections(
                        origin.lon + "," + origin.lat,
                        destination.lon + "," + destination.lat,
                        "RECOMMEND"
                ).enqueue(new Callback<DirectionsResponse>() {
                    @Override
                    public void onResponse(Call<DirectionsResponse> call, Response<DirectionsResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            DirectionsResponse data = response.body();
                            if (data.routes != null && !data.routes.isEmpty()) {
                                Route route = data.routes.get(0);

                                // vertex 정보 추출
                                List<Vertex> vertices = new ArrayList<>();
                                Log.d(TAG, String.format("===== Route Vertices from %s to %s =====",
                                        origin.name, destination.name));

                                if (route.sections != null && !route.sections.isEmpty()) {
                                    Route.Section section = route.sections.get(0);
                                    if (section.roads != null) {
                                        int roadCount = 0;
                                        for (Route.Road road : section.roads) {
                                            Log.d(TAG, String.format("--- Road %d ---", ++roadCount));
                                            if (road.vertexes != null) {
                                                // vertex는 [lon1, lat1, lon2, lat2, ...] 형식으로 제공됨
                                                for (int i = 0; i < road.vertexes.length; i += 2) {
                                                    double lat = road.vertexes[i+1];
                                                    double lon = road.vertexes[i];
                                                    vertices.add(new Vertex(lat, lon));
                                                    Log.d(TAG, String.format("Vertex: (lat: %f, lon: %f)", lat, lon));
                                                }
                                            }
                                        }
                                    }
                                }
                                Log.d(TAG, String.format("Total vertices in route: %d", vertices.size()));
                                Log.d(TAG, "=====================================");

                                RouteInfo routeInfo = new RouteInfo(
                                        origin.name,
                                        destination.name,
                                        route.summary.distance,
                                        route.summary.duration,
                                        vertices
                                );

                                // 그래프에 경로와 vertex 정보 추가
                                graph.addEdge(origin.name, destination.name, route.summary.duration);
                                graph.addVertexPath(origin.name, destination.name, vertices);

                                Log.d(TAG, String.format("Added route: %s -> %s with %d vertices",
                                        origin.name, destination.name, vertices.size()));

                                // 모든 요청이 완료되었는지 확인
                                if (completedRequests.incrementAndGet() == totalRequests.get()) {
                                    Log.d(TAG, "All routes calculated. Calling callback...");
                                    printGraphState();
                                    callback.onSuccess(allRoutes);
                                }
                            }
                        }
                    }
                    @Override
                    public void onFailure(Call<DirectionsResponse> call, Throwable t) {
                        Log.e(TAG, "API call failed", t);
                        if (completedRequests.incrementAndGet() == totalRequests.get()) {
                            printGraphState();
                        }
                    }
                });
            }
        }
    }


    // Request/Response 클래스들
    private static class DirectionsRequest {
        @SerializedName("origin")
        final Coordinates origin;
        @SerializedName("destination")
        final Coordinates destination;
        @SerializedName("priority")
        final String priority = "RECOMMEND";

        DirectionsRequest(double fromLat, double fromLon, double toLat, double toLon) {
            this.origin = new Coordinates(fromLat, fromLon);
            this.destination = new Coordinates(toLat, toLon);
        }
    }
    private static class DirectionsResponse {
        @SerializedName("routes")
        List<Route> routes;
    }

    private static class Coordinates {
        @SerializedName("x")
        final double x;
        @SerializedName("y")
        final double y;

        Coordinates(double lat, double lon) {
            this.x = lon;  // 경도
            this.y = lat;  // 위도
        }
    }


    private static class Route {
        @SerializedName("summary")
        Summary summary;

        @SerializedName("sections")
        List<Section> sections;

        static class Section {
            @SerializedName("roads")
            List<Road> roads;
        }

        static class Road {
            @SerializedName("vertexes")
            double[] vertexes;  // [lon1, lat1, lon2, lat2, ...]
        }
    }

    private static class Summary {
        @SerializedName("distance")
        int distance;
        @SerializedName("duration")
        int duration;
    }

    private static class Point {
        final String name;
        final double lat;
        final double lon;

        Point(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }
}