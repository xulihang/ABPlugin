package com.ab.abplugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import anywheresoftware.b4a.BA;
import anywheresoftware.b4a.BA.Author;
import anywheresoftware.b4a.BA.DesignerName;
import anywheresoftware.b4a.BA.Events;
import anywheresoftware.b4a.BA.ShortName;
import anywheresoftware.b4a.BA.Version;

@DesignerName("Build 20160528")
@Version(1.00F)
@Author("Alain Bailleul")
@ShortName("ABPlugin")
@Events(values={"PluginsChanged()"})
public class ABPlugin {
	protected BA _ba;
	protected String _event;
	private String pluginsDir;
	private Map<String, ABPluginDefinition> plugins = new LinkedHashMap<String, ABPluginDefinition>();
	protected boolean mIsRunning=false;
	protected boolean mPluginIsRunning=false;
	protected boolean mIsLoading=false;
	protected ScheduledExecutorService service = null;
	protected Future<?> future = null;
	protected long CheckForNewIntervalMS=0;
	private static URLClassLoader parentClassLoader;
	private String AllowedKey="";
	private final ReadWriteLock pluginsLock = new ReentrantReadWriteLock();

	public void Initialize(BA ba, String eventName, String pluginsDir, String allowedKey) {
		this._ba = ba;
		this._event = eventName.toLowerCase(BA.cul);
		this.pluginsDir = pluginsDir;
		this.AllowedKey=allowedKey;
		// Compatible with JDK9+ classloader retrieval
		ClassLoader ctxClassLoader = Thread.currentThread().getContextClassLoader();
		// In JDK9+, the context classloader might not be a direct URLClassLoader instance
		parentClassLoader = ctxClassLoader instanceof java.net.URLClassLoader ? 
			(java.net.URLClassLoader)ctxClassLoader : null;
		File f = new File(pluginsDir);
		if (!f.exists()) {
			try {
				Files.createDirectories(Paths.get(pluginsDir));
				BA.Log("Created plugins directory: " + pluginsDir);
			} catch (IOException e) {
				BA.Log("Failed to create plugins directory: " + e.getMessage());
				e.printStackTrace();
			}
		} else if (!f.isDirectory()) {
			BA.Log("Plugins path exists but is not a directory: " + pluginsDir);
		}
	}
	
	public anywheresoftware.b4a.objects.collections.List GetAvailablePlugins() {
		anywheresoftware.b4a.objects.collections.List ret = new anywheresoftware.b4a.objects.collections.List();
		ret.Initialize();
		pluginsLock.readLock().lock();
		try {
			for (Entry<String,ABPluginDefinition> entry : plugins.entrySet()) {
				ret.Add(entry.getValue().NiceName);
			}
		} finally {
			pluginsLock.readLock().unlock();
		}
		return ret;
	}
	
	Runnable runnable = new Runnable() {
	    public void run() {
	    	if (mIsRunning && !mPluginIsRunning) {
	    		mIsLoading=true;
	    		Map<String, Boolean> toRemove = new LinkedHashMap<String,Boolean>();
				List<String> toAdd = new ArrayList<String>();
				
				// Get plugins to remove
				pluginsLock.readLock().lock();
				try {
					for (Entry<String,ABPluginDefinition> entry: plugins.entrySet()) {
						toRemove.put(entry.getKey(), true);
					}
				} finally {
					pluginsLock.readLock().unlock();
				}
				
				boolean NeedsReload=false;
				File dh = new File(pluginsDir);
				if (!dh.exists() || !dh.isDirectory()) {
					BA.Log("Invalid plugins directory: " + pluginsDir);
					mIsLoading=false;
					return;
				}
				
				File[] files = dh.listFiles();
				if (files == null) {
					BA.Log("Failed to list files in plugins directory: " + pluginsDir);
					mIsLoading=false;
					return;
				}
				
				for (File f: files) {
					if (f.getName().endsWith(".jar")) {
						String pluginName = f.getName().substring(0, f.getName().length()-4).toLowerCase();
						toRemove.remove(pluginName);
						
						// Check if plugin needs update
						boolean needsUpdate = false;
						pluginsLock.readLock().lock();
						try {
							if (!plugins.containsKey(pluginName)) {
								toAdd.add(f.getAbsolutePath());
							} else {
								ABPluginDefinition def = plugins.get(pluginName);
								if (f.lastModified() != def.lastModified) {
									needsUpdate = true;
								}
							}
						} finally {
							pluginsLock.readLock().unlock();
						}
						
						if (needsUpdate) {
							toRemove.put(pluginName, true);
						}
					}
				}
				
				// Process plugins to remove
				if (!toRemove.isEmpty()) {
					BA.Log("Reloading plugins due to updates: " + toRemove.keySet());
					pluginsLock.writeLock().lock();
					try {
						plugins.clear();
					} finally {
						pluginsLock.writeLock().unlock();
					}
					toAdd.clear();
					for (File f: files) {
						if (f.getName().endsWith(".jar")) {
							toAdd.add(f.getAbsolutePath());
						}
					}
				}
				
				boolean Added = false;
				for (int i=0; i<toAdd.size(); i++) {
					File f = new File(toAdd.get(i));
					if (!f.exists() || !f.canRead()) {
						BA.Log("Cannot read JAR file: " + f.getAbsolutePath());
						continue;
					}
					
					ABPluginDefinition def = new ABPluginDefinition();
					def.lastModified = f.lastModified();
					if (loadJarFile(pluginsDir, f, parentClassLoader, def)) {
						if (RunInitialize(def)) {
							String niceName = innerGetNiceName(def);
							if (niceName != null && !niceName.isEmpty()) {
								def.NiceName = niceName;
								pluginsLock.writeLock().lock();
								try {
									plugins.put(def.Name.toLowerCase(), def);
								} finally {
									pluginsLock.writeLock().unlock();
								}
								Added = true;
								BA.Log("Successfully loaded plugin: " + def.NiceName);
							} else {
								BA.Log("Plugin returned empty NiceName: " + def.Name);
							}
						}
					}
				}
				
				if (NeedsReload || Added) {
					BA.Log("Plugins changed, raising event");
					_ba.raiseEvent(this, _event + "_pluginschanged", new Object[] {});
				}
				mIsLoading=false;
			}
		}
	};
	
	private boolean RunInitialize(ABPluginDefinition def) {
		java.lang.reflect.Method m;
		try {
			m = def.objectClass.getMethod("_initialize", new Class[]{anywheresoftware.b4a.BA.class});
			m.setAccessible(true);
			Object result = m.invoke(def.object, new Object[] {_ba});
			if (result instanceof String) {
				String returnedKey = (String) result;
				// Use equals method instead of == to compare strings
				boolean keyMatch = (AllowedKey != null && AllowedKey.equals(returnedKey));
				if (!keyMatch) {
					BA.Log("Plugin initialization failed: Invalid key for " + def.Name);
				}
				return keyMatch;
			} else {
				BA.Log("Plugin initialization returned non-string value for " + def.Name);
				return false;
			}
		} catch (NoSuchMethodException e) {
			BA.Log("_initialize method not found in plugin: " + def.Name + " - " + e.getMessage());
		} catch (SecurityException e) {
			BA.Log("Security error accessing initialization method for " + def.Name + " - " + e.getMessage());
		} catch (IllegalAccessException e) {
			BA.Log("Illegal access to initialization method for " + def.Name + " - " + e.getMessage());
		} catch (IllegalArgumentException e) {
			BA.Log("Illegal argument in initialization for " + def.Name + " - " + e.getMessage());
		} catch (InvocationTargetException e) {
			BA.Log("Plugin initialization exception for " + def.Name + " - " + e.getTargetException().getMessage());
		}
		return false;
	}
	
	public Object RunPlugin(String pluginNiceName, String tag, anywheresoftware.b4a.objects.collections.Map params) {
		if (pluginNiceName == null || pluginNiceName.isEmpty()) {
			BA.Log("Empty plugin name provided");
			return null;
		}
		
		mPluginIsRunning=true;
		ABPluginDefinition def=null;
		pluginsLock.readLock().lock();
		try {
			for (Entry<String,ABPluginDefinition> entry: plugins.entrySet()) {
				if (entry.getValue().NiceName != null && entry.getValue().NiceName.equalsIgnoreCase(pluginNiceName)) {
					def = entry.getValue();
					break;
				}
			}
		} finally {
			pluginsLock.readLock().unlock();
		}
		
		if (def==null) {
			BA.Log("No plugin found with name: '" + pluginNiceName + "'");
			mPluginIsRunning=false;
			return null;
		}
		
		java.lang.reflect.Method m;
		try {
			m = GetMethod(def, "_run");
			if (m==null) {
				BA.Log("'Sub Run(Tag As String, Params As Map) As Object' not found in plugin: " + def.Name);
				mPluginIsRunning=false;
				return "";
			}
			m.setAccessible(true);
			Object ret = m.invoke(def.object, new Object[] {tag, params});
			mPluginIsRunning=false;
			return ret;
		} catch (SecurityException e) {
			BA.Log("Security error running plugin " + def.Name + " - " + e.getMessage());
		} catch (IllegalAccessException e) {
			BA.Log("Illegal access to run method for plugin " + def.Name + " - " + e.getMessage());
		} catch (IllegalArgumentException e) {
			BA.Log("Illegal argument running plugin " + def.Name + " - " + e.getMessage());
		} catch (InvocationTargetException e) {
			BA.Log("Exception running plugin " + def.Name + " - " + e.getTargetException().getMessage());
		}
		mPluginIsRunning=false;
		return null;
	}
	
	protected String innerGetNiceName(ABPluginDefinition def) {
		mPluginIsRunning=true;
		java.lang.reflect.Method m;
		try {
			m = GetMethod(def, "_getnicename");
			if (m==null) {
				BA.Log("'Sub GetNiceName() As String' not found in plugin: " + def.Name + ", using filename as fallback");
				mPluginIsRunning=false;
				return def.Name; // Use filename as fallback
			}
			m.setAccessible(true);
			Object ret = m.invoke(def.object, new Object[] {});
			mPluginIsRunning=false;
			return (ret instanceof String) ? (String)ret : def.Name;
		} catch (SecurityException e) {
			BA.Log("Security error getting nice name for " + def.Name + " - " + e.getMessage());
		} catch (IllegalAccessException e) {
			BA.Log("Illegal access to getnicename method for " + def.Name + " - " + e.getMessage());
		} catch (IllegalArgumentException e) {
			BA.Log("Illegal argument getting nice name for " + def.Name + " - " + e.getMessage());
		} catch (InvocationTargetException e) {
			BA.Log("Exception getting nice name for " + def.Name + " - " + e.getTargetException().getMessage());
		}
		mPluginIsRunning=false;
		return def.Name; // Return filename on error
	}
	
	protected java.lang.reflect.Method GetMethod(ABPluginDefinition def, String methodName) {
		if (def == null || def.objectClass == null) {
			BA.Log("Invalid plugin definition or class when looking for method: " + methodName);
			return null;
		}
		
		Class<?> clazz = def.objectClass;
		while (clazz != null) {
			java.lang.reflect.Method[] methods;
			try {
				// In JDK9+, need to handle potential access restrictions
				methods = clazz.getDeclaredMethods();
			} catch (SecurityException e) {
				BA.Log("Security exception accessing methods in class " + clazz.getName() + ": " + e.getMessage());
				clazz = clazz.getSuperclass();
				continue;
			}
			
		    for (java.lang.reflect.Method method : methods) {
		        if (method.getName().equals(methodName)) {
		            try {
		                // Ensure method accessibility, adapt to JDK9+ module system restrictions
		                method.setAccessible(true);
		                return method;
		            } catch (SecurityException e) {
		                BA.Log("Failed to make method accessible: " + methodName + " in class " + clazz.getName());
		            }
		        }
		    }
		    clazz = clazz.getSuperclass();
		}
		return null;
	}
	
	public void Start(long checkForNewIntervalMS) {
		if (checkForNewIntervalMS <= 0) {
			BA.Log("Invalid interval for plugin checking: " + checkForNewIntervalMS);
			return;
		}
		
		this.CheckForNewIntervalMS = checkForNewIntervalMS;
		try {
			if (service != null) {
				service.shutdown();
				service.awaitTermination(500, TimeUnit.MILLISECONDS);
			}
			service = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread thread = new Thread(r, "ABPlugin-Loader");
				thread.setDaemon(true);
				return thread;
			});
			future = service.scheduleAtFixedRate(runnable, 0, checkForNewIntervalMS, TimeUnit.MILLISECONDS);
			mIsRunning = true;
			BA.Log("Plugin system started with check interval: " + checkForNewIntervalMS + "ms");
		} catch (Exception e) {
			BA.Log("Failed to start plugin system: " + e.getMessage());
		}
	}
	
	public void Stop() {
		if (mIsRunning) {
			mIsRunning = false;
			if (future != null) {
				future.cancel(true);
			}
			if (service != null) {
				service.shutdown();
				try {
					service.awaitTermination(1000, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					// Properly handle thread interruption in JDK9+
					Thread.currentThread().interrupt();
					BA.Log("Thread interrupted while waiting for plugin service termination: " + e.getMessage());
				}
			}
			BA.Log("Plugin system stopped");
		}
	}
	
	public void Pauze() {
		if (mIsRunning) {
			mIsRunning = false;
			if (future != null) {
				future.cancel(true);
			}
			BA.Log("Plugin system paused");
		}
	}
	
	public void Resume() {
		if (!mIsRunning && service != null && !service.isShutdown()) {
			future = service.scheduleAtFixedRate(runnable, 0, CheckForNewIntervalMS, TimeUnit.MILLISECONDS);
			mIsRunning = true;
			BA.Log("Plugin system resumed");
		} else {
			BA.Log("Cannot resume plugin system - it's either running or shutdown");
		}
	}
	
	private boolean loadJarFile(String directoryName, File pluginFile, ClassLoader parentClassLoader, ABPluginDefinition def) {
        URL url = null;
        try {
            // More reliable URL construction
            url = pluginFile.toURI().toURL();
            // Ensure JAR URL format
            String urlStr = url.toString();
            if (!urlStr.endsWith("!/") && urlStr.endsWith(".jar")) {
                url = new URL("jar:" + urlStr + "!/");
            }
        } catch (MalformedURLException e) {
            BA.Log("Failed to create URL for JAR file: " + pluginFile.getAbsolutePath() + " - " + e.getMessage());
            return false;
        }
        URL[] urls = { url };
 
        JarFile jarFile = null;
        try {
            jarFile = new JarFile(pluginFile);
        } catch (IOException e) {
            BA.Log("Failed to open JAR file: " + pluginFile.getAbsolutePath() + " - " + e.getMessage());
            return false;
        }
        
        def.Name = pluginFile.getName().substring(0, pluginFile.getName().length()-4);
 
        // Collect classes from JAR file
        List<String> classes = new ArrayList<>();
        try {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                classes.add(entry.getName().replace(".class", "").replace('/', '.'));
            }
        } catch (Exception e) {
            BA.Log("Error scanning JAR file contents: " + pluginFile.getAbsolutePath() + " - " + e.getMessage());
            try {
                jarFile.close();
            } catch (IOException eClose) {
                BA.Log("Failed to close JAR file after scanning error: " + eClose.getMessage());
            }
            return false;
        }
        
        // If no classes found, log warning
        if (classes.isEmpty()) {
            BA.Log("No classes found in JAR file: " + pluginFile.getAbsolutePath());
            try {
                jarFile.close();
            } catch (IOException eClose) {
                BA.Log("Failed to close empty JAR file: " + eClose.getMessage());
            }
            return false;
        }
        
        def.objectClass=null;
        URLClassLoader classLoader = null;
        try {
            // Compatible with JDK9+, use system classloader when parentClassLoader is null
            classLoader = new URLClassLoader(urls, parentClassLoader != null ? parentClassLoader : ClassLoader.getSystemClassLoader());
            
            // Try to find main class (class matching JAR name)
            boolean foundMainClass = false;
            String mainClassName = null;
            
            // First try exact match
            for (String className : classes) {
                if (className.toLowerCase().endsWith("." + def.Name.toLowerCase()) || className.equalsIgnoreCase(def.Name)) {
                    mainClassName = className;
                    foundMainClass = true;
                    break;
                }
            }
            
            // If no exact match, try to find class with _getnicename method
            if (!foundMainClass && classes.size() > 0) {
                BA.Log("No exact class match found for " + def.Name + " in " + pluginFile.getAbsolutePath() + ", trying to find class with _getnicename method");
                
                // Try to find class with _getnicename method
                for (String className : classes) {
                    try {
                        Class<?> tempClass = classLoader.loadClass(className);
                        // Check if the class has _getnicename method
                        try {
                            tempClass.getMethod("_getnicename");
                            mainClassName = className;
                            foundMainClass = true;
                            BA.Log("Found class with _getnicename method: " + className);
                            break;
                        } catch (NoSuchMethodException e) {
                            // This class doesn't have the method, continue searching
                        }
                    } catch (ClassNotFoundException e) {
                        // Class not found, continue searching
                    }
                }
                
                // If no class with _getnicename found, use first class as main class
                if (!foundMainClass) {
                    BA.Log("No class with _getnicename method found, using first class");
                    mainClassName = classes.get(0);
                    foundMainClass = true;
                }
            }
            
            if (foundMainClass && mainClassName != null) {
                try {
                    def.objectClass = classLoader.loadClass(mainClassName);
                    def.object = def.objectClass.newInstance();
                    BA.Log("Loaded plugin class: " + mainClassName);
                } catch (Exception e) {
                    BA.Log("Failed to load or instantiate main class " + mainClassName + " - " + e.getMessage());
                }
            }
            
            // Load other classes to ensure they are available in class loader
            for (String className : classes) {
                try {
                    if (!className.equals(mainClassName)) {
                        classLoader.loadClass(className);
                    }
                } catch (ClassNotFoundException e) {
                    BA.Log("Failed to preload class: " + className);
                    // Continue loading other classes, don't stop due to single class loading failure
                }
            }
            
        } catch (Exception e) {
            BA.Log("Error loading classes from JAR: " + pluginFile.getAbsolutePath() + " - " + e.getMessage());
            return false;
        } finally {
            // Close resources
            try {
                if (classLoader != null) {
                    // Compatible closing approach for JDK7+ and JDK9+
                    // In JDK9+, URLClassLoader directly implements Closeable interface
                    classLoader.close();
                }
            } catch (IOException e) {
                BA.Log("Error closing class loader: " + e.getMessage());
            }
            
            try {
                if (jarFile != null) {
                    jarFile.close();
                }
            } catch (IOException e) {
                BA.Log("Failed to close JAR file: " + e.getMessage());
            }
        }
        
        return def.objectClass != null;
	}
}
