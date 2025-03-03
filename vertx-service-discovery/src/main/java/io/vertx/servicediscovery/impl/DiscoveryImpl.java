/*
 * Copyright (c) 2011-2016 The original author or authors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.servicediscovery.impl;

import io.vertx.core.*;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.servicediscovery.*;
import io.vertx.servicediscovery.spi.ServiceDiscoveryBackend;
import io.vertx.servicediscovery.spi.ServiceExporter;
import io.vertx.servicediscovery.spi.ServiceImporter;
import io.vertx.servicediscovery.spi.ServicePublisher;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Default implementation of the service discovery.
 *
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class DiscoveryImpl implements ServiceDiscovery, ServicePublisher {

  private final Vertx vertx;
  private final String announce;
  private final String usage;
  private final ServiceDiscoveryBackend backend;

  private final Set<ServiceImporter> importers = new CopyOnWriteArraySet<>();
  private final Set<ServiceExporter> exporters = new CopyOnWriteArraySet<>();
  private final Set<ServiceReference> bindings = new CopyOnWriteArraySet<>();
  private final static Logger LOGGER = LoggerFactory.getLogger(DiscoveryImpl.class.getName());
  private final String id;
  private final ServiceDiscoveryOptions options;


  public DiscoveryImpl(Vertx vertx, ServiceDiscoveryOptions options) {
    this(vertx, options, getBackend(options.getBackendConfiguration().getString("backend-name", null)));
  }

  /**
   * Creates a new instance of {@link DiscoveryImpl}
   *
   * @param vertx   the vert.x instance
   * @param options the options
   * @param backend the backend service
   */
  DiscoveryImpl(Vertx vertx, ServiceDiscoveryOptions options, ServiceDiscoveryBackend backend) {
    this.vertx = vertx;
    this.announce = options.getAnnounceAddress();
    this.usage = options.getUsageAddress();

    this.backend = backend;
    this.backend.init(vertx, options.getBackendConfiguration());
    this.id = options.getName() != null ? options.getName() : getNodeId(vertx);
    this.options = options;
  }


  public void initialize(Handler<ServiceDiscovery> completionHandler) {
    Collection<ServiceImporter> spi = getServiceImporterFromSPI();
    Map<Future, ServiceImporter> map = new HashMap<>();
    List<Future> futures = spi.stream().map(imp -> {
      Promise<Void> promise = Promise.promise();
      imp.start(vertx, this, new JsonObject(), promise);
      map.put(promise.future(), imp);
      return promise.future();
    }).collect(Collectors.toList());

    CompositeFuture.join(futures)
      .setHandler(ar -> {
        for (Future f : futures) {
          ServiceImporter serviceImporter = map.get(f);
          if (f.succeeded()) {
            LOGGER.info("Auto-registration of importer " + serviceImporter);
            importers.add(serviceImporter);
          } else {
            LOGGER.warn("Failed to register importer " + serviceImporter);
          }
        }
        completionHandler.handle(this);
      });
  }

  private String getNodeId(Vertx vertx) {
    if (vertx.isClustered()) {
      return ((VertxInternal) vertx).getNodeID();
    } else {
      return "localhost";
    }
  }

  private static ServiceDiscoveryBackend getBackend(String maybeName) {
    ServiceLoader<ServiceDiscoveryBackend> backends = ServiceLoader.load(ServiceDiscoveryBackend.class);
    Iterator<ServiceDiscoveryBackend> iterator = backends.iterator();

    if (maybeName == null) {
      if (!iterator.hasNext()) {
        return new DefaultServiceDiscoveryBackend();
      } else {
        return iterator.next();
      }
    }

    if (maybeName.equals(DefaultServiceDiscoveryBackend.class.getName())) {
      return new DefaultServiceDiscoveryBackend();
    }

    // We have a name
    while (iterator.hasNext()) {
      ServiceDiscoveryBackend backend = iterator.next();
      if (backend.name().equals(maybeName)) {
        return backend;
      }
    }

    throw new IllegalStateException("Cannot find the discovery backend implementation with name " + maybeName + " in " +
      "the classpath");
  }


  private Collection<ServiceImporter> getServiceImporterFromSPI() {
    ServiceLoader<ServiceImporter> importers = ServiceLoader.load(ServiceImporter.class);
    Iterator<ServiceImporter> iterator = importers.iterator();

    List<ServiceImporter> list = new ArrayList<>();
    // We have a name
    while (iterator.hasNext()) {
      ServiceImporter importer = iterator.next();
      list.add(importer);
    }

    return list;
  }


  @Override
  public ServiceReference getReference(Record record) {
    return getReferenceWithConfiguration(record, new JsonObject());
  }

  @Override
  public ServiceReference getReferenceWithConfiguration(Record record, JsonObject configuration) {
    ServiceReference reference = ServiceTypes.get(record).get(vertx, this, record, configuration);
    bindings.add(reference);
    sendBindEvent(reference);
    return reference;
  }

  private void sendBindEvent(ServiceReference reference) {
    if (usage == null) {
      return;
    }
    vertx.eventBus().publish(usage, new JsonObject()
      .put("type", "bind")
      .put("record", reference.record().toJson())
      .put("id", id));
  }

  @Override
  public boolean release(ServiceReference reference) {
    boolean removed = bindings.remove(reference);
    reference.release();
    sendUnbindEvent(reference);
    return removed;
  }

  private void sendUnbindEvent(ServiceReference reference) {
    if (usage == null) {
      return;
    }
    vertx.eventBus().publish(usage, new JsonObject()
      .put("type", "release")
      .put("record", reference.record().toJson())
      .put("id", id));
  }

  @Override
  public ServiceDiscovery registerServiceImporter(ServiceImporter importer, JsonObject configuration,
                                                  Handler<AsyncResult<Void>> completionHandler) {
    JsonObject conf;
    if (configuration == null) {
      conf = new JsonObject();
    } else {
      conf = configuration;
    }

    Promise<Void> completed = Promise.promise();
    completed.future().setHandler(
      ar -> {
        if (ar.failed()) {
          LOGGER.error("Cannot start the service importer " + importer, ar.cause());
          if (completionHandler != null) {
            completionHandler.handle(Future.failedFuture(ar.cause()));
          }
        } else {
          importers.add(importer);
          LOGGER.info("Service importer " + importer + " started");

          if (completionHandler != null) {
            completionHandler.handle(Future.succeededFuture(null));
          }
        }
      }
    );

    importer.start(vertx, this, conf, completed);
    return this;
  }

  @Override
  public ServiceDiscovery registerServiceImporter(ServiceImporter importer, JsonObject configuration) {
    return registerServiceImporter(importer, configuration, null);
  }

  @Override
  public ServiceDiscovery registerServiceExporter(ServiceExporter exporter, JsonObject configuration) {
    return registerServiceExporter(exporter, configuration, null);
  }

  @Override
  public ServiceDiscovery registerServiceExporter(ServiceExporter exporter, JsonObject configuration,
                                                  Handler<AsyncResult<Void>> completionHandler) {
    JsonObject conf;
    if (configuration == null) {
      conf = new JsonObject();
    } else {
      conf = configuration;
    }

    Promise<Void> completed = Promise.promise();
    completed.future().setHandler(
      ar -> {
        if (ar.failed()) {
          LOGGER.error("Cannot start the service importer " + exporter, ar.cause());
          if (completionHandler != null) {
            completionHandler.handle(Future.failedFuture(ar.cause()));
          }
        } else {
          exporters.add(exporter);
          LOGGER.info("Service exporter " + exporter + " started");

          if (completionHandler != null) {
            completionHandler.handle(Future.succeededFuture(null));
          }
        }
      }
    );

    exporter.init(vertx, this, conf, completed);
    return this;
  }

  @Override
  public void close() {
    LOGGER.info("Stopping service discovery");
    List<Future> futures = new ArrayList<>();
    for (ServiceImporter importer : importers) {
      Promise<Void> promise = Promise.promise();
      importer.close(v -> promise.complete());
      futures.add(promise.future());
    }

    for (ServiceExporter exporter : exporters) {
      Promise<Void> promise = Promise.promise();
      exporter.close(promise::complete);
      futures.add(promise.future());
    }

    bindings.forEach(ServiceReference::release);
    bindings.clear();

    CompositeFuture.all(futures).setHandler(ar -> {
      if (ar.succeeded()) {
        LOGGER.info("Discovery bridges stopped");
      } else {
        LOGGER.warn("Some discovery bridges did not stopped smoothly", ar.cause());
      }
    });
  }

  @Override
  public void publish(Record record, Handler<AsyncResult<Record>> resultHandler) {
    Status status = record.getStatus() == null || record.getStatus() == Status.UNKNOWN
      ? Status.UP : record.getStatus();

    backend.store(record.setStatus(status), ar -> {
      if (ar.failed()) {
        resultHandler.handle(Future.failedFuture(ar.cause()));
        return;
      }

      for (ServiceExporter exporter : exporters) {
        exporter.onPublish(new Record(ar.result()));
      }
      Record announcedRecord = new Record(ar.result());
      announcedRecord
        .setRegistration(null)
        .setStatus(status);

      vertx.eventBus().publish(announce, announcedRecord.toJson());
      resultHandler.handle(Future.succeededFuture(ar.result()));
    });
  }

  @Override
  public void unpublish(String id, Handler<AsyncResult<Void>> resultHandler) {
    backend.remove(id, record -> {
      if (record.failed()) {
        resultHandler.handle(Future.failedFuture(record.cause()));
        return;
      }

      for (ServiceExporter exporter : exporters) {
        exporter.onUnpublish(id);
      }

      Record announcedRecord = new Record(record.result());
      announcedRecord
        .setRegistration(null)
        .setStatus(Status.DOWN);

      vertx.eventBus().publish(announce, announcedRecord.toJson());
      resultHandler.handle(Future.succeededFuture());
    });

  }

  @Override
  public void getRecord(JsonObject filter,
                        Handler<AsyncResult<Record>> resultHandler) {
    boolean includeOutOfService = false;
    Function<Record, Boolean> accept;
    if (filter == null) {
      accept = r -> true;
    } else {
      includeOutOfService = filter.getString("status") != null;
      accept = r -> r.match(filter);
    }

    getRecord(accept, includeOutOfService, resultHandler);
  }

  @Override
  public void getRecord(Function<Record, Boolean> filter, Handler<AsyncResult<Record>> resultHandler) {
    getRecord(filter, false, resultHandler);
  }

  @Override
  public void getRecord(Function<Record, Boolean> filter, boolean includeOutOfService, Handler<AsyncResult<Record>>
    resultHandler) {
    Objects.requireNonNull(filter);
    backend.getRecords(list -> {
      if (list.failed()) {
        resultHandler.handle(Future.failedFuture(list.cause()));
      } else {
        Optional<Record> any = list.result().stream()
          .filter(filter::apply)
          .filter(record -> includeOutOfService || record.getStatus() == Status.UP)
          .findAny();
        if (any.isPresent()) {
          resultHandler.handle(Future.succeededFuture(any.get()));
        } else {
          resultHandler.handle(Future.succeededFuture(null));
        }
      }
    });
  }

  @Override
  public void getRecords(JsonObject filter, Handler<AsyncResult<List<Record>>> resultHandler) {
    boolean includeOutOfService = false;
    Function<Record, Boolean> accept;
    if (filter == null) {
      accept = r -> true;
    } else {
      includeOutOfService = filter.getString("status") != null;
      accept = r -> r.match(filter);
    }

    getRecords(accept, includeOutOfService, resultHandler);
  }

  @Override
  public void getRecords(Function<Record, Boolean> filter, Handler<AsyncResult<List<Record>>> resultHandler) {
    getRecords(filter, false, resultHandler);
  }

  @Override
  public void getRecords(Function<Record, Boolean> filter, boolean includeOutOfService, Handler<AsyncResult<List<Record>>> resultHandler) {
    Objects.requireNonNull(filter);
    backend.getRecords(list -> {
      if (list.failed()) {
        resultHandler.handle(Future.failedFuture(list.cause()));
      } else {
        resultHandler.handle(Future.succeededFuture(
          list.result().stream()
            .filter(filter::apply)
            .filter(record -> includeOutOfService || record.getStatus() == Status.UP)
            .collect(Collectors.toList())
        ));
      }
    });
  }

  @Override
  public void update(Record record, Handler<AsyncResult<Record>> resultHandler) {
    backend.update(record, ar -> {
      if (ar.failed()) {
        resultHandler.handle(Future.failedFuture(ar.cause()));
      } else {
        resultHandler.handle(Future.succeededFuture(record));
      }
    });

    for (ServiceExporter exporter : exporters) {
      exporter.onUpdate(record);
    }

    Record announcedRecord = new Record(record);
    vertx.eventBus().publish(announce, announcedRecord.toJson());
  }

  @Override
  public Set<ServiceReference> bindings() {
    return new HashSet<>(bindings);
  }

  @Override
  public ServiceDiscoveryOptions options() {
    return options;
  }

  /**
   * Checks whether the reference is hold by this service discovery. If so, remove it from the list of bindings and
   * fire the "release" event.
   *
   * @param reference the reference
   */
  public void unbind(ServiceReference reference) {
    if (bindings.remove(reference)) {
      sendUnbindEvent(reference);
    }
  }
}

