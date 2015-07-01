package org.segrada.servlet;

import com.google.inject.Inject;
import com.google.inject.Injector;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.tika.io.IOUtils;
import org.segrada.model.prototype.SegradaEntity;
import org.segrada.service.PictogramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

@Provider
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
public class SegradaMessageBodyReader implements MessageBodyReader<SegradaEntity> {
	/**
	 * get injector - not very nice, but works
	 */
	@Inject
	private Injector injector;

	private static final Logger logger = LoggerFactory.getLogger(SegradaMessageBodyReader.class);

	@Override
	public boolean isReadable(Class<?> aClass, Type type, Annotation[] annotations, MediaType mediaType) {
		return SegradaEntity.class.isAssignableFrom(aClass);
	}

	@Override
	public SegradaEntity readFrom(Class<SegradaEntity> aClass, Type type, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> multivaluedMap, InputStream inputStream) throws IOException, WebApplicationException {
		// create instance
		SegradaEntity entity;
		try {
			entity = aClass.newInstance();
		} catch (Exception e) {
			logger.error("Could not convert to entity: " + aClass, e);
			return null;
		}

		// map setter methods to value map
		Map<String, Method> setters = new HashMap<>();
		for (Method method : aClass.getMethods()) {
			String methodName = method.getName();
			if (methodName.startsWith("set")) {
				methodName = methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
				setters.put(methodName, method);
			}
		}

		// read form data
		StringWriter writer = new StringWriter();
		IOUtils.copy(inputStream, writer, "UTF-8");
		String theString = writer.toString();

		// get each form key and value
		for (NameValuePair nameValuePair : URLEncodedUtils.parse(theString, Charset.forName("UTF-8"))) {
			Method setter = setters.get(nameValuePair.getName());
			if (setter != null) {
				// get first parameter of setter (type to cast)
				Class setterType = setter.getParameterTypes()[0];

				// preprocess values
				Object value = nameValuePair.getValue();
				if (value != null && !nameValuePair.getValue().isEmpty()) {
					if (nameValuePair.getName().equals("color") && nameValuePair.getValue().startsWith("#")) {
						try {
							if (nameValuePair.getValue().equals("#ffffffff")) value = null;
							else value = Integer.decode(nameValuePair.getValue());
						} catch (NumberFormatException e) {
							value = null;
							// fail silently
						}
					}
				}
				// handle segrada entities
				if (SegradaEntity.class.isAssignableFrom(setterType)) {
					PictogramService service = injector.getInstance(PictogramService.class);
					value = service.findById((String) value);
				}
				// handle arrays
				if (String[].class.isAssignableFrom(setterType)) {
					// get current property value
					String getterName = "g" + setter.getName().substring(1);
					try {
						Method getter = entity.getClass().getMethod(getterName);
						String[] currentValues = (String[]) getter.invoke(entity);
						if (currentValues == null) {
							value = new String[] { (String) value };
						} else {
							// add to string
							String[] newValues = new String[currentValues.length+1];
							for (int i = 0; i < currentValues.length; i++)
								newValues[i] = currentValues[i];
							newValues[currentValues.length] = (String)value;
							value = newValues;
						}
					} catch (Exception e) {
						logger.error("Could not get getter for " + aClass.getSimpleName() + ", method " + setter.getName() + " in order to assign array values", e);
						value = null;
					}
				}

				// try to set value
				try {
					// invoke setter on entity
					if (value == null) setter.invoke(entity, value);
					else setter.invoke(entity, setterType.cast(value));
				} catch (Exception e) {
					logger.error("Could not set entity of type " + aClass.getSimpleName() + ", method " + setter.getName() + " to value " + value + " (type " + setterType.getName() + ")", e);
				}
			}
		}

		return entity;
	}
}
