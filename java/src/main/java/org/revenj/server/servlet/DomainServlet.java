package org.revenj.server.servlet;

import org.revenj.patterns.*;
import org.revenj.server.ProcessingEngine;
import org.revenj.server.commands.GetDomainObject;
import org.revenj.server.commands.SubmitEvent;
import org.revenj.server.commands.search.SearchDomainObject;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Optional;

public class DomainServlet extends HttpServlet {

	private static final Charset UTF8 = Charset.forName("UTF-8");

	private final DomainModel model;
	private final ProcessingEngine engine;
	private final WireSerialization serialization;

	public DomainServlet(DomainModel model, ProcessingEngine engine, WireSerialization serialization) {
		this.model = model;
		this.engine = engine;
		this.serialization = serialization;
	}

	DomainServlet(ServiceLocator locator) {
		this(locator.resolve(DomainModel.class), locator.resolve(ProcessingEngine.class), locator.resolve(WireSerialization.class));
	}


	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		String path = req.getPathInfo();
		if (path.startsWith("/search/")) {
			Optional<String> name = findType(path, "/search/", res);
			if (name.isPresent()) {
				String spec = req.getParameter("specification");
				if (spec != null) {
					res.sendError(405, "Parsing specification from URL argument not yet supported. Use PUT method instead");
				} else {
					Integer limit = req.getParameter("limit") != null ? Integer.parseInt(req.getParameter("limit")) : null;
					Integer offset = req.getParameter("offset") != null ? Integer.parseInt(req.getParameter("offset")) : null;
					SearchDomainObject.Argument arg = new SearchDomainObject.Argument(name.get(), null, null, offset, limit, null);
					Utility.executeJson(engine, res, SearchDomainObject.class, arg);
				}
			}
		} else {
			res.sendError(405, "Unknown URL path: " + path);
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		res.setContentType("application/json");
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		String path = req.getPathInfo();
		if (path.startsWith("/find/")) {
			String[] uris = serialization.deserialize(String[].class, req.getInputStream(), req.getContentType());
			findType(path, "/find/", res).ifPresent(name -> {
				GetDomainObject.Argument arg = new GetDomainObject.Argument(name, uris, "match".equals(req.getParameter("order")));
				Utility.executeJson(engine, res, GetDomainObject.class, arg);
			});
		} else if (path.startsWith("/search/")) {
			Optional<String> name = findType(path, "/search/", res);
			if (name.isPresent()) {
				Integer limit = req.getParameter("limit") != null ? Integer.parseInt(req.getParameter("limit")) : null;
				Integer offset = req.getParameter("offset") != null ? Integer.parseInt(req.getParameter("offset")) : null;
				String spec = req.getParameter("specification");
				SearchDomainObject.Argument arg;
				if (spec != null) {
					Optional<Class<?>> specType = model.find(name.get() + "$" + spec);
					if (!specType.isPresent()) {
						specType = model.find(spec);
					}
					if (!specType.isPresent()) {
						res.sendError(400, "Couldn't find specification: " + spec);
						return;
					}
					try {
						Specification specification = (Specification) serialization.deserialize(specType.get(), req.getInputStream(), req.getContentType());
						arg = new SearchDomainObject.Argument(name.get(), null, specification, offset, limit, null);
					} catch (IOException e) {
						res.sendError(400, "Error deserializing specification. " + e.getMessage());
						return;
					}
				} else {
					arg = new SearchDomainObject.Argument(name.get(), null, null, offset, limit, null);
				}
				Utility.executeJson(engine, res, SearchDomainObject.class, arg);
			}
		} else if (path.startsWith("/count/")) {
			Optional<String> name = findType(path, "/count/", res);
			if (name.isPresent()) {

			}
		} else if (path.startsWith("/exists/")) {
			Optional<String> name = findType(path, "/exists/", res);
			if (name.isPresent()) {

			}
		} else if (path.startsWith("/submit/")) {
			processSubmitEvent(path, req, res);
		} else {
			res.sendError(405, "Unknown URL path: " + path);
		}
	}

	@Override
	protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		res.setContentType("application/json");
	}

	private Optional<String> findType(String path, String prefix, HttpServletResponse res) throws IOException {
		String name = path.substring(prefix.length(), path.length());
		Optional<Class<?>> manifest = model.find(name);
		if (!manifest.isPresent()) {
			res.sendError(400, "Unknown domain object: " + name);
			return Optional.empty();
		}
		return Optional.of(name);
	}

	private void processSubmitEvent(String path, HttpServletRequest req, HttpServletResponse res) throws IOException {
		String name = path.substring("/submit/".length(), path.length());
		Optional<Class<?>> manifest = model.find(name);
		if (!manifest.isPresent()) {
			res.sendError(400, "Unknown domain object: " + name);
			return;
		}
		if (manifest.get().isAssignableFrom(DomainEvent.class)) {
			res.sendError(400, "Specified type is not an domain event: " + name);
			return;
		}
		DomainEvent domainEvent = (DomainEvent)serialization.deserialize(manifest.get(), req.getInputStream(), req.getContentType());
		SubmitEvent.Argument arg = new SubmitEvent.Argument<>(name, domainEvent, "instance".equals(req.getParameter("return")));
		Utility.executeJson(engine, res, SubmitEvent.class, arg);
	}
}