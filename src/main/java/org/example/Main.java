package org.example;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.resources.LoopResources;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

public class Main {

    private static final Map<Integer, Integer> limites = new HashMap<Integer, Integer>() {{
        put(1, 100000);
        put(2, 80000);
        put(3, 1000000);
        put(4, 10000000);
        put(5, 500000);
    }};

    private static final String DB_URL = "jdbc:postgresql://db:5432/rinha";
    private static final String DB_USER = "admin";
    private static final String DB_PASSWORD = "123";

    private static ObjectMapper objectMapper = new ObjectMapper();
    private static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");


    private static HikariDataSource dataSource = null;

    public static void main(String[] args) {
        Main m = new Main();
        m.go();
    }
    private static HikariDataSource createDataSource() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException("os scripts demoram");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DB_URL);
        config.setUsername(DB_USER);
        config.setPassword(DB_PASSWORD);
        return new HikariDataSource(config);
    }
    public static void go() {
        String portString = System.getenv("API_PORTA");
        int port = 8080;
        if (portString != null) {
            port = Integer.parseInt(portString);
        }

        LoopResources loop = LoopResources.create("event-loop", 1, 40, true);

        HttpServer httpServer = HttpServer.create().runOn(loop).port(port)
                .route(routes ->
                        routes.get("/hello", hello())
                                .post("/clientes/{id}/transacoes", (request, response) -> transacao(request, response, dataSource))
                                .get("/clientes/{id}/extrato", (request, response) -> extrato(request, response, dataSource))
                );
        httpServer.warmup().block();

        dataSource = createDataSource();

        DisposableServer server = httpServer.bindNow();
        server.onDispose().block();
    }

    private static Mono<Void> extrato(HttpServerRequest request, HttpServerResponse response, DataSource ds) {
        Integer id = Integer.parseInt(request.param("id"));
        Integer limite = limites.get(id);
        if (limite == null) {
            return response.status(404).sendString(Mono.just("{}")).then();
        }

        try (Connection connection = ds.getConnection()) {
            CallableStatement callableStatement = connection.prepareCall("{call public.extrato" + id + "()}");
            ResultSet resultSet = callableStatement.executeQuery();

            boolean primeiro = true;
            HashMap<String, Object> retorno = new HashMap<String, Object>();
            ArrayList<Map<String, Object>> transacoes = new ArrayList<Map<String, Object>>();
            retorno.put("ultimas_transacoes", transacoes);
            HashMap<String, Object> saldo = new HashMap<String, Object>();
            retorno.put("saldo", saldo);

            Integer saldoTemp = 0;
            while (resultSet.next()) {
                if (primeiro) {
                    primeiro = false;
                    saldoTemp = resultSet.getInt("a");
                }
                HashMap<String, Object> transacao = new HashMap<String, Object>();
                transacoes.add(transacao);
                transacao.put("valor", resultSet.getInt("b"));
                transacao.put("tipo", resultSet.getString("c"));
                transacao.put("descricao", resultSet.getString("d"));
                transacao.put("realizada_em", resultSet.getTimestamp("e").toLocalDateTime().atOffset(ZoneOffset.UTC).format(formatter));
            }

            saldo.put("total", saldoTemp);
            saldo.put("limite", limite);
            saldo.put("data_extrato", LocalDateTime.now().atOffset(ZoneOffset.UTC).format(formatter));

            resultSet.close();

            String json = objectMapper.writeValueAsString(retorno);

            return response.status(200).sendString(Mono.just(json)).then();
        } catch (Exception e) {
            e.printStackTrace();
            return response.status(500).sendString(Mono.just("Erro")).then();
        }
    }

    private static Mono<Void> transacao(HttpServerRequest request, HttpServerResponse response, DataSource ds) {

        return request.receive().aggregate().asString()
                .flatMap(body -> {
                    Integer id = Integer.parseInt(request.param("id"));
                    Integer limite = limites.get(id);
                    if (limite == null) {
                        return response.status(404).sendString(Mono.just("{}")).then();
                    }

                    try {
                        Map<String, Object> data = objectMapper.readValue(body, Map.class);

                        Object valorObject = data.get("valor");
                        Integer valor = null;
                        if (valorObject instanceof Integer) {
                            valor = (Integer) valorObject;
                        } else {
                            return response.status(422).sendString(Mono.just("Erro")).then();
                        }
                        String tipo = (String) data.get("tipo");
                        Object descricao = data.get("descricao");
                        if (descricao != null) {
                            if (descricao instanceof  String) {
                                String d = (String) descricao;
                                if (d.length() > 10 || d.length() < 1) {
                                    return response.status(422).sendString(Mono.just("Erro")).then();
                                }
                            } else {
                                return response.status(422).sendString(Mono.just("Erro")).then();
                            }
                        } else {
                            return response.status(422).sendString(Mono.just("Erro")).then();
                        }

                        Integer novoSaldo = null;
                        try (Connection connection = ds.getConnection()) {
                            if (tipo.equals("c")) {
                                CallableStatement callableStatement = connection.prepareCall("{call public.credito" + id + "(" + valor + ",'" + descricao + "')}");
                                ResultSet resultSet = callableStatement.executeQuery();
                                resultSet.next();
                                novoSaldo = resultSet.getInt("cc");
                                resultSet.close();
                            } else if (tipo.equals("d")) {
                                CallableStatement callableStatement = connection.prepareCall("{call public.debito" + id + "(" + valor + ",'" + descricao + "', " + limite + ")}");
                                ResultSet resultSet = callableStatement.executeQuery();
                                resultSet.next();
                                novoSaldo = resultSet.getInt("dd");
                                if (novoSaldo == null) {
                                    return response.status(422).sendString(Mono.just("Erro")).then();
                                }
                                resultSet.close();
                            } else {
                                return response.status(422).sendString(Mono.just("Erro")).then();
                            }
                            return response.status(200).sendString(Mono.just("{\"limite\": " + limite + ", \"saldo\": " + novoSaldo + "}")).then();
                        } catch (Exception e) {
                            e.printStackTrace();
                            return response.status(500).sendString(Mono.just("Erro")).then();
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                        return response.status(500).sendString(Mono.just("Erro")).then();
                    }

                });

    }

    private static BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> hello() {
        return (request, response) -> response.sendString(Mono.just("Hello World!"));
    }

}