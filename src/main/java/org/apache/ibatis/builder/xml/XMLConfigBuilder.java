/**
 * Copyright 2009-2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

    private boolean parsed;
    private final XPathParser parser;
    private String environment;
    private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

    public XMLConfigBuilder(Reader reader) {
        this(reader, null, null);
    }

    public XMLConfigBuilder(Reader reader, String environment) {
        this(reader, environment, null);
    }

    /**
     * 所有的public修饰的XMLConfigBuilder构造方法都会调用private XMLConfigBuilder(XPathParser parser, String environment, Properties props)方法
     * 构造全局唯一的Configuration
     * @param reader
     * @param environment
     * @param props
     */
    public XMLConfigBuilder(Reader reader, String environment, Properties props) {
        // 调用 XMLConfigBuilder(XPathParser parser, String environment, Properties props)方法
        this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    public XMLConfigBuilder(InputStream inputStream) {
        this(inputStream, null, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment) {
        this(inputStream, environment, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
        // 调用 XMLConfigBuilder(XPathParser parser, String environment, Properties props)方法
        this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
        super(new Configuration());
        ErrorContext.instance().resource("SQL Mapper Configuration");
        this.configuration.setVariables(props);
        this.parsed = false;
        this.environment = environment;
        this.parser = parser;
    }

    public Configuration parse() {
        // 是否已经被解析过
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        parsed = true;

        // 调用parseConfiguration方法将Configuration元素下的所有配置信息封装进Parser对象的成员Configuration对象之中
        // 其中进行解析xml元素的方式是通过evalNode方法获取对应名称的节点信息。
        // 如：parseConfiguration(parser.evalNode("/configuration"));，此时parser.evalNode("/configuration")即为Configuration节点下的所有信息。
        // parseConfiguration方法相当于将里面每个元素的信息都单独封装到Configuration中。
        parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }

    /**
     * 将/configuration节点下的子节点信息添加到Configuration对象中
     * @param root
     */
    private void parseConfiguration(XNode root) {
        try {
            // issue #117 read properties first
            //properties解析 这句读取的是<configuration>下的<properties>节点
            propertiesElement(root.evalNode("properties"));
            // 解析setting节点
            Properties settings = settingsAsProperties(root.evalNode("settings"));
            loadCustomVfs(settings);
            loadCustomLogImpl(settings);
            //类型别名解析 解析的是<configuration>下的<typeAliases>标签
            typeAliasesElement(root.evalNode("typeAliases"));
            // 解析插件
            pluginElement(root.evalNode("plugins"));
            // 自定义对象工程加载
            objectFactoryElement(root.evalNode("objectFactory"));
            //5.对象包装工厂
            objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
            reflectorFactoryElement(root.evalNode("reflectorFactory"));

            // mybatis 设置
            settingsElement(settings);
            // read it after objectFactory and objectWrapperFactory issue #631
            environmentsElement(root.evalNode("environments"));
            //解析databaseIdProvider元素，配置数据库类型唯一标志生成器
            databaseIdProviderElement(root.evalNode("databaseIdProvider"));

            typeHandlerElement(root.evalNode("typeHandlers"));
            // !! 解析mapper
            mapperElement(root.evalNode("mappers"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
    }

    private Properties settingsAsProperties(XNode context) {
        if (context == null) {
            return new Properties();
        }
        // 将节点解析成键值对的形式（Properties是Hashtable的子类），看一下props的toString方法打印的内容：
        Properties props = context.getChildrenAsProperties();
        // Check that all settings are known to the configuration class
        // 反射出Configuration.class
        MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
        // 检查是否Configuration是否有与节点对应的字段
        for (Object key : props.keySet()) {
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        return props;
    }

    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        String value = props.getProperty("vfsImpl");
        if (value != null) {
            String[] clazzes = value.split(",");
            for (String clazz : clazzes) {
                if (!clazz.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    private void loadCustomLogImpl(Properties props) {
        Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);
    }

    /**
     * <typeAliases>标签下可以定义<package>和<typeAlias>两种标签
     * @param parent
     */
    private void typeAliasesElement(XNode parent) {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                //解析<package>标签
                if ("package".equals(child.getName())) {
                    String typeAliasPackage = child.getStringAttribute("name");
                    // 就将<package>标签name属性路径下的Class（如果符合要求），全部放到了HashMap typeAliases 中以供使用
                    configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
                //解析<typeAlias>标签
                } else {
                    //解析<typeAlias>中的alias属性，再解析<typeAlias>中的type属性
                    String alias = child.getStringAttribute("alias");
                    String type = child.getStringAttribute("type");
                    try {
                        Class<?> clazz = Resources.classForName(type);
                        if (alias == null) {
                            typeAliasRegistry.registerAlias(clazz);
                        } else {
                            typeAliasRegistry.registerAlias(alias, clazz);
                        }
                    } catch (ClassNotFoundException e) {
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    /**
     * 解析插件
     * @param parent
     * @throws Exception
     */
    private void pluginElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                // 拿<plugin>标签中的interceptor属性
                String interceptor = child.getStringAttribute("interceptor");
                Properties properties = child.getChildrenAsProperties();
                // 通过反射生成拦截器实例
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).getDeclaredConstructor().newInstance();

                interceptorInstance.setProperties(properties);
                // 拦截器设置到Configuration中
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    /**
     *   <configuration>
     *     ......
     *     <objectFactory type="org.mybatis.example.ExampleObjectFactory">
     *         <property name="someProperty" value="100"/>
     *     </objectFactory>
     *     ......
     *   </configuration
     * @param context
     * @throws Exception
     */
    private void objectFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            // 工厂属性
            Properties properties = context.getChildrenAsProperties();
            // 反射出工厂对象
            ObjectFactory factory = (ObjectFactory) resolveClass(type).getDeclaredConstructor().newInstance();
            // 设置属性
            factory.setProperties(properties);
            configuration.setObjectFactory(factory);
        }
    }

    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).getDeclaredConstructor().newInstance();
            configuration.setObjectWrapperFactory(factory);
        }
    }

    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).getDeclaredConstructor().newInstance();
            configuration.setReflectorFactory(factory);
        }
    }

    private void propertiesElement(XNode context) throws Exception {
        if (context != null) {
            Properties defaults = context.getChildrenAsProperties();
            String resource = context.getStringAttribute("resource");
            String url = context.getStringAttribute("url");
            if (resource != null && url != null) {
                throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
            }
            if (resource != null) {
                defaults.putAll(Resources.getResourceAsProperties(resource));
            } else if (url != null) {
                defaults.putAll(Resources.getUrlAsProperties(url));
            }
            Properties vars = configuration.getVariables();
            if (vars != null) {
                defaults.putAll(vars);
            }
            parser.setVariables(defaults);
            configuration.setVariables(defaults);
        }
    }

    private void settingsElement(Properties props) {
        configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
        configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
        configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
        configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
        configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
        configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
        configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
        configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
        configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
        configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
        configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));
        configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
        configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
        configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
        configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
        configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
        configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
        configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        configuration.setLogPrefix(props.getProperty("logPrefix"));
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
        configuration.setShrinkWhitespacesInSql(booleanValueOf(props.getProperty("shrinkWhitespacesInSql"), false));
    }

    private void environmentsElement(XNode context) throws Exception {
        if (context != null) {
            // 得到默认的JDBC环境名称
            if (environment == null) {
                environment = context.getStringAttribute("default");
            }
            //遍历<environments>标签下的每一个<environment>标签
            for (XNode child : context.getChildren()) {
                // 获取<environment>下的id属性
                String id = child.getStringAttribute("id");
                // 判断当前的<environment>是不是默认的JDBC环境
                if (isSpecifiedEnvironment(id)) {
                    //根据<transactionManager>标签获取事物管理器
                    TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
                    //根据<dataSource>标签获取数据源工厂DataSourceFactory
                    DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
                    // 根据DataSourceFactory获取DataSource
                    DataSource dataSource = dsFactory.getDataSource();
                    // 使用内部类构建的方式根据TransactionFactory和DataSource创建一个Environment并设置到Configuration
                    Environment.Builder environmentBuilder = new Environment.Builder(id)
                            .transactionFactory(txFactory)
                            .dataSource(dataSource);
                    configuration.setEnvironment(environmentBuilder.build());
                }
            }
        }
    }

    /**
     * 解析 databaseIdProvider节点
     *
     * @param context databaseIdProvider节点
     */
    private void databaseIdProviderElement(XNode context) throws Exception {
        DatabaseIdProvider databaseIdProvider = null;
        if (context != null) {
            String type = context.getStringAttribute("type");
            // awful patch to keep backward compatibility
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            // 获取用户定义的数据库类型和databaseId的配置
            Properties properties = context.getChildrenAsProperties();
            // 获取databaseIdProvider实例
            databaseIdProvider = (DatabaseIdProvider) resolveClass(type).getDeclaredConstructor().newInstance();
            // 配置数据库类型和databaseId的对应关系
            databaseIdProvider.setProperties(properties);
        }
        // 获取Environment容器
        Environment environment = configuration.getEnvironment();
        if (environment != null && databaseIdProvider != null) {
            // 获取当前环境的databaseId
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
            // 同步configuration#databaseId的值
            configuration.setDatabaseId(databaseId);
        }
    }

    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            Properties props = context.getChildrenAsProperties();
            TransactionFactory factory = (TransactionFactory) resolveClass(type).getDeclaredConstructor().newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            Properties props = context.getChildrenAsProperties();
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).getDeclaredConstructor().newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    private void typeHandlerElement(XNode parent) {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                if ("package".equals(child.getName())) {
                    String typeHandlerPackage = child.getStringAttribute("name");
                    typeHandlerRegistry.register(typeHandlerPackage);
                } else {
                    String javaTypeName = child.getStringAttribute("javaType");
                    String jdbcTypeName = child.getStringAttribute("jdbcType");
                    String handlerTypeName = child.getStringAttribute("handler");
                    Class<?> javaTypeClass = resolveClass(javaTypeName);
                    JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
                    Class<?> typeHandlerClass = resolveClass(handlerTypeName);
                    if (javaTypeClass != null) {
                        if (jdbcType == null) {
                            typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
                        } else {
                            typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
                        }
                    } else {
                        typeHandlerRegistry.register(typeHandlerClass);
                    }
                }
            }
        }
    }

  private void mapperElement(XNode parent) throws Exception {
    //如果有配置mappers标签
    if (parent != null) {
      //遍历mappers标签下的所有package标签和mapper标签
      for (XNode child : parent.getChildren()) {
        //如果child为package标签
        if ("package".equals(child.getName())) {
          //获取package标签指定的映射包名路径（相对于类路径的资源引用）
          String mapperPackage = child.getStringAttribute("name");
          //将映射包名路径添加到mybatis全局配置信息中
          configuration.addMappers(mapperPackage);
        //这里else表示的是mapper标签
        } else {
          //resource表示使用相对于类路径的资源引用
          String resource = child.getStringAttribute("resource");
          //url表示使用完全限定资源定位符（URL）
          String url = child.getStringAttribute("url");
          //使用映射器接口实现类的完全限定类名
          String mapperClass = child.getStringAttribute("class");
          //如果配置了resource 但是 url以及mapperClass没有配置
          if (resource != null && url == null && mapperClass == null) {
            //设置错误报文实例的资源引用为resource
            ErrorContext.instance().resource(resource);
            //获取resource文件输入流
            InputStream inputStream = Resources.getResourceAsStream(resource);
            //新建一个XML映射文件构建器
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            /**
             * 解析Mapper.xml，解析mapper标签下的所有标签，并对解析出来的标签信息加以封装，
             * 然后添加到Mybatis全局配置信息中。然后重新解析Mybatis全局配置信息中未能完成解析的
             * ResultMap标签信息，CacheRef标签信息，DML标签信息
             */
            mapperParser.parse();
            //如果配置了url但是resource以及mapperClass没有配置
          } else if (resource == null && url != null && mapperClass == null) {
            //设置错误报文实例的资源引用为url
            ErrorContext.instance().resource(url);
            //获取url文件输入流
            InputStream inputStream = Resources.getUrlAsStream(url);
            //新建一个XML映射文件构建器
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            /**
             * 解析Mapper.xml，解析mapper标签下的所有标签，并对解析出来的标签信息加以封装，
             * 然后添加到Mybatis全局配置信息中。然后重新解析Mybatis全局配置信息中未能完成解析的
             * ResultMap标签信息，CacheRef标签信息，DML标签信息
             */
            mapperParser.parse();
            //如果配置了mapperClass但是resource以及url没有配置
          } else if (resource == null && url == null && mapperClass != null) {
            //加载mapperClass对应的java类
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            //将mapperInterface加入到mapperRegistry中
            configuration.addMapper(mapperInterface);
          } else {
            //如果把url,resource,mapperClass都配置，就会抛出异常
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

    /**
     * <environments>标签下的default属性是一个必填属性
     * @param id
     * @return
     */
    private boolean isSpecifiedEnvironment(String id) {
        if (environment == null) {
            throw new BuilderException("No environment specified.");
        } else if (id == null) {
            throw new BuilderException("Environment requires an id attribute.");
        } else if (environment.equals(id)) {
            return true;
        }
        return false;
    }

}
