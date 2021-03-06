/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2014 wcm.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package io.wcm.handler.media.testcontext;

import io.wcm.config.spi.ApplicationProvider;
import io.wcm.config.spi.ConfigurationFinderStrategy;
import io.wcm.config.spi.ParameterProvider;
import io.wcm.handler.media.format.impl.MediaFormatProviderManagerImpl;
import io.wcm.handler.media.spi.MediaFormatProvider;
import io.wcm.handler.url.UrlParams;
import io.wcm.handler.url.impl.UrlHandlerParameterProviderImpl;
import io.wcm.sling.commons.resource.ImmutableValueMap;
import io.wcm.testing.mock.aem.junit.AemContext;
import io.wcm.testing.mock.aem.junit.AemContextCallback;
import io.wcm.testing.mock.wcmio.config.MockConfig;
import io.wcm.testing.mock.wcmio.sling.MockSlingExtensions;

import java.io.IOException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.testing.mock.sling.ResourceResolverType;

/**
 * Sets up {@link AemContext} for unit tests in this application.
 */
public final class MediaSourceInlineAppAemContext {

  /**
   * Appliation ID
   */
  public static final String APPLICATION_ID = "/apps/test";

  /**
   * DAM root path
   */
  public static final String DAM_PATH = "/content/dam/test";

  /**
   * Content root path
   */
  public static final String ROOTPATH_CONTENT = "/content/unittest/de_test/brand/de";

  private MediaSourceInlineAppAemContext() {
    // static methods only
  }

  public static AemContext newAemContext() {
    return new AemContext(new SetUpCallback(),
        ResourceResolverType.RESOURCERESOLVER_MOCK,
        ResourceResolverType.JCR_MOCK);
  }

  /**
   * Custom set up rules required in all unit tests.
   */
  private static final class SetUpCallback implements AemContextCallback {

    @Override
    public void execute(AemContext context) throws PersistenceException, IOException {

      // wcm.io Sling extensions
      MockSlingExtensions.setUp(context);

      // URL handler-specific parameter definitions
      context.registerService(ParameterProvider.class, new UrlHandlerParameterProviderImpl());

      // application provider
      context.registerService(ApplicationProvider.class,
          MockConfig.applicationProvider(APPLICATION_ID, "/content"));

      // configuration finder strategy
      context.registerService(ConfigurationFinderStrategy.class,
          MockConfig.configurationFinderStrategyAbsoluteParent(APPLICATION_ID,
              DummyUrlHandlerConfig.SITE_ROOT_LEVEL));

      // wcm.io configuration
      MockConfig.setUp(context);

      // media formats
      context.registerService(MediaFormatProvider.class, new DummyMediaFormatProvider());
      context.registerInjectActivateService(new MediaFormatProviderManagerImpl());

      // sling models registration
      context.addModelsForPackage("io.wcm.handler.url");
      context.addModelsForPackage("io.wcm.handler.media");
      context.addModelsForPackage("io.wcm.handler.mediasource.inline");

      // create current page in site context
      context.currentPage(context.create().page(ROOTPATH_CONTENT,
          DummyAppTemplate.CONTENT.getTemplatePath()));

      // default site config
      MockConfig.writeConfiguration(context, ROOTPATH_CONTENT,
          ImmutableValueMap.builder()
          .put(UrlParams.SITE_URL.getName(), "http://www.dummysite.org")
          .put(UrlParams.SITE_URL_SECURE.getName(), "https://www.dummysite.org")
          .put(UrlParams.SITE_URL_AUTHOR.getName(), "https://author.dummysite.org")
          .build());
    }

  }

}
