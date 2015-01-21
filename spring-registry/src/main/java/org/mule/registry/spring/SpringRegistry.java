/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.registry.spring;


import static org.apache.commons.lang.StringUtils.EMPTY;
import org.mule.api.MuleContext;
import org.mule.api.MuleException;
import org.mule.api.MuleRuntimeException;
import org.mule.api.lifecycle.Initialisable;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.registry.InitialisingRegistry;
import org.mule.api.registry.RegistrationException;
import org.mule.config.i18n.MessageFactory;
import org.mule.lifecycle.RegistryLifecycleManager;
import org.mule.lifecycle.phases.NotInLifecyclePhase;
import org.mule.registry.AbstractRegistry;
import org.mule.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

public class SpringRegistry extends AbstractRegistry implements InitialisingRegistry
{

    public static final String REGISTRY_ID = "org.mule.Registry.Spring";

    /**
     * Key used to lookup Spring Application Context from SpringRegistry via Mule's
     * Registry interface.
     */
    public static final String SPRING_APPLICATION_CONTEXT = "springApplicationContext";

    protected ApplicationContext applicationContext;

    private boolean readOnly;

    private RegistrationDelegate registrationDelegate;

    //This is used to track the Spring context lifecycle since there is no way to confirm the
    //lifecycle phase from the application context
    protected AtomicBoolean springContextInitialised = new AtomicBoolean(false);

    public SpringRegistry(ApplicationContext applicationContext, MuleContext muleContext)
    {
        super(REGISTRY_ID, muleContext);
        setApplicationContext(applicationContext);
    }

    public SpringRegistry(String id, ApplicationContext applicationContext, MuleContext muleContext)
    {
        super(id, muleContext);
        setApplicationContext(applicationContext);
    }

    public SpringRegistry(ConfigurableApplicationContext applicationContext, ApplicationContext parentContext, MuleContext muleContext)
    {
        super(REGISTRY_ID, muleContext);
        applicationContext.setParent(parentContext);
        setApplicationContext(applicationContext);
    }

    public SpringRegistry(String id, ConfigurableApplicationContext applicationContext, ApplicationContext parentContext, MuleContext muleContext)
    {
        super(id, muleContext);
        applicationContext.setParent(parentContext);
        setApplicationContext(applicationContext);
    }

    private void setApplicationContext(ApplicationContext applicationContext)
    {
        this.applicationContext = applicationContext;
        if (applicationContext instanceof ConfigurableApplicationContext)
        {
            readOnly = false;
            registrationDelegate = new ConfigurableRegistrationDelegate((ConfigurableApplicationContext) applicationContext);
        }
        else
        {
            readOnly = true;
            registrationDelegate = new ReadOnlyRegistrationDelegate();
        }
    }

    @Override
    protected void doInitialise() throws InitialisationException
    {
        if (applicationContext instanceof ConfigurableApplicationContext)
        {
            ((ConfigurableApplicationContext) applicationContext).refresh();
        }
        //This is used to track the Spring context lifecycle since there is no way to confirm the lifecycle phase from the application context
        springContextInitialised.set(true);
    }

    @Override
    public void doDispose()
    {
        // check we aren't trying to close a context which has never been started,
        // spring's appContext.isActive() isn't working for this case
        if (!this.springContextInitialised.get())
        {
            return;
        }

        if (applicationContext instanceof ConfigurableApplicationContext
            && ((ConfigurableApplicationContext) applicationContext).isActive())
        {
            ((ConfigurableApplicationContext) applicationContext).close();
        }

        // release the circular implicit ref to MuleContext
        applicationContext = null;

        this.springContextInitialised.set(false);
    }

    @Override
    protected RegistryLifecycleManager createLifecycleManager()
    {
        return new SpringRegistryLifecycleManager(getRegistryId(), this, muleContext);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object lookupObject(String key)
    {
        if (StringUtils.isBlank(key))
        {
            logger.warn(
                    MessageFactory.createStaticMessage("Detected a lookup attempt with an empty or null key").getMessage(),
                    new Throwable().fillInStackTrace());
            return null;
        }

        if (key.equals(SPRING_APPLICATION_CONTEXT) && applicationContext != null)
        {
            return applicationContext;
        }
        else
        {
            try
            {
                return applicationContext.getBean(key);
            }
            catch (NoSuchBeanDefinitionException e)
            {
                logger.debug(e.getMessage(), e);
                return null;
            }
        }
    }

    @Override
    public <T> Collection<T> lookupObjects(Class<T> type)
    {
        return lookupByType(type).values();
    }

    @Override
    public <T> Collection<T> lookupLocalObjects(Class<T> type)
    {
        return internalLookupByTypeWithoutAncestors(type, true, true).values();
    }

    /**
     * For lifecycle we only want spring to return singleton objects from it's application context
     */
    @Override
    public <T> Collection<T> lookupObjectsForLifecycle(Class<T> type)
    {
        return internalLookupByTypeWithoutAncestors(type, false, false).values();
    }

    @Override
    public <T> Map<String, T> lookupByType(Class<T> type)
    {
        return internalLookupByType(type, true, true);
    }

    protected <T> Map<String, T> internalLookupByType(Class<T> type, boolean nonSingletons, boolean eagerInit)
    {
        try
        {
            return BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, type, nonSingletons, eagerInit);
        }
        catch (FatalBeanException fbex)
        {
            // FBE is a result of a broken config, propagate it (see MULE-3297 for more details)
            String message = String.format("Failed to lookup beans of type %s from the Spring registry", type);
            throw new MuleRuntimeException(MessageFactory.createStaticMessage(message), fbex);
        }
        catch (Exception e)
        {
            logger.debug(e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    protected <T> Map<String, T> internalLookupByTypeWithoutAncestors(Class<T> type, boolean nonSingletons, boolean eagerInit)
    {
        try
        {
            return applicationContext.getBeansOfType(type, nonSingletons, eagerInit);
        }
        catch (FatalBeanException fbex)
        {
            // FBE is a result of a broken config, propagate it (see MULE-3297 for more details)
            String message = String.format("Failed to lookup beans of type %s from the Spring registry", type);
            throw new MuleRuntimeException(MessageFactory.createStaticMessage(message), fbex);
        }
        catch (Exception e)
        {
            logger.debug(e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    @Override
    public void registerObject(String key, Object value) throws RegistrationException
    {
        registrationDelegate.registerObject(key, value);
    }

    @Override
    public void registerObject(String key, Object value, Object metadata) throws RegistrationException
    {
        registrationDelegate.registerObject(key, value, metadata);

        try
        {
            if (getLifecycleManager().getCurrentPhase().equals(NotInLifecyclePhase.PHASE_NAME))
            {
                if (value instanceof Initialisable)
                {
                    ((Initialisable) value).initialise();
                }
            }
            else
            {
                applyLifecycle(value);
            }
        }
        catch (MuleException e)
        {
            throw new RegistrationException(e);
        }
    }

    @Override
    public void registerObjects(Map<String, Object> objects) throws RegistrationException
    {
        registrationDelegate.registerObjects(objects);
    }

    @Override
    public void unregisterObject(String key)
    {
        registrationDelegate.unregisterObject(key);
    }

    @Override
    public void unregisterObject(String key, Object metadata) throws RegistrationException
    {
        registrationDelegate.unregisterObject(key, metadata);
    }

    /**
     * Will fire any lifecycle methods according to the current lifecycle without actually
     * registering the object in the registry.  This is useful for prototype objects that are created per request and would
     * clutter the registry with single use objects.
     *
     * @param object the object to process
     * @return the same object with lifecycle methods called (if it has any)
     * @throws org.mule.api.MuleException if the registry fails to perform the lifecycle change for the object.
     */
    @Override
    public Object applyLifecycle(Object object) throws MuleException
    {
        getLifecycleManager().applyCompletedPhases(object);
        return object;
    }

    @Override
    public Object applyLifecycle(Object object, String phase) throws MuleException
    {
        if (phase == null)
        {
            getLifecycleManager().applyCompletedPhases(object);
        }
        else
        {
            getLifecycleManager().applyPhase(object, NotInLifecyclePhase.PHASE_NAME, phase);
        }
        return object;
    }

    @Override
    public Object applyProcessors(Object object, Object metadata)
    {
        return initialiseObject((ConfigurableApplicationContext) applicationContext, EMPTY, object);
    }

    private Object initialiseObject(ConfigurableApplicationContext applicationContext, String key, Object object)
    {
        return applicationContext.getBeanFactory().initializeBean(object, key);
    }

    private interface RegistrationDelegate
    {

        void registerObject(String key, Object value) throws RegistrationException;

        void registerObject(String key, Object value, Object metadata) throws RegistrationException;

        void registerObjects(Map<String, Object> objects) throws RegistrationException;

        void unregisterObject(String key);

        void unregisterObject(String key, Object metadata) throws RegistrationException;
    }

    private class ConfigurableRegistrationDelegate implements RegistrationDelegate
    {

        private final ConfigurableApplicationContext applicationContext;

        private ConfigurableRegistrationDelegate(ConfigurableApplicationContext applicationContext)
        {
            this.applicationContext = applicationContext;
        }

        @Override
        public void registerObject(String key, Object value) throws RegistrationException
        {
            doRegisterObject(key, value);
        }

        @Override
        public void registerObject(String key, Object value, Object metadata) throws RegistrationException
        {
            registerObject(key, value);
        }

        @Override
        public void registerObjects(Map<String, Object> objects) throws RegistrationException
        {
            if (objects == null || objects.isEmpty())
            {
                return;
            }

            for (Map.Entry<String, Object> entry : objects.entrySet())
            {
                registerObject(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public void unregisterObject(String key)
        {
            ((BeanDefinitionRegistry) applicationContext.getBeanFactory()).removeBeanDefinition(key);
        }

        @Override
        public void unregisterObject(String key, Object metadata) throws RegistrationException
        {
            unregisterObject(key);
        }

        private void doRegisterObject(String key, Object value)
        {
            value = initialiseObject(applicationContext, key, value);
            applicationContext.getBeanFactory().registerSingleton(key, value);
        }
    }

    private class ReadOnlyRegistrationDelegate implements RegistrationDelegate
    {

        @Override
        public void registerObject(String key, Object value) throws RegistrationException
        {
            throwException();
        }

        @Override
        public void registerObject(String key, Object value, Object metadata) throws RegistrationException
        {
            throwException();
        }

        @Override
        public void registerObjects(Map<String, Object> objects) throws RegistrationException
        {
            throwException();
        }

        @Override
        public void unregisterObject(String key)
        {
            throwException();
        }

        @Override
        public void unregisterObject(String key, Object metadata) throws RegistrationException
        {
            throwException();
        }

        private void throwException()
        {
            throw new UnsupportedOperationException("Registry is read-only so objects cannot be registered or unregistered.");
        }


    }

    ////////////////////////////////////////////////////////////////////////////////////
    // Registry meta-data
    ////////////////////////////////////////////////////////////////////////////////////

    @Override
    public boolean isReadOnly()
    {
        return readOnly;
    }

    @Override
    public boolean isRemote()
    {
        return false;
    }
}