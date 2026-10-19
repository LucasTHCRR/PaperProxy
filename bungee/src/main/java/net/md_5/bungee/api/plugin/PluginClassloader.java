/*
 * Copyright (c) 2012, md_5. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * The name of the author may not be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * You may not use the software for commercial software hosting services without
 * written permission from the author.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * Modified for PaperProxy: the class loader uses the BungeeCord layer's class
 * loader as parent instead of the system class loader, and Lombok was removed.
 */
package net.md_5.bungee.api.plugin;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.md_5.bungee.api.ProxyServer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

final class PluginClassloader extends URLClassLoader
{

    private static final Set<PluginClassloader> allLoaders = new CopyOnWriteArraySet<>();
    //
    private final ProxyServer proxy;
    private final PluginDescription desc;
    private final JarFile jar;
    private final Manifest manifest;
    private final URL url;
    private final ClassLoader libraryLoader;
    //
    private Plugin plugin;

    static
    {
        ClassLoader.registerAsParallelCapable();
    }

    public PluginClassloader(ProxyServer proxy, PluginDescription desc, File file, ClassLoader libraryLoader) throws IOException
    {
        super( new URL[]
        {
            file.toURI().toURL()
        }, PluginClassloader.class.getClassLoader() );
        this.proxy = proxy;
        this.desc = desc;
        this.jar = new JarFile( file );
        this.manifest = jar.getManifest();
        this.url = file.toURI().toURL();
        this.libraryLoader = libraryLoader;

        allLoaders.add( this );
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        return loadClass0( name, resolve, true, true );
    }

    private Class<?> loadClass0(String name, boolean resolve, boolean checkOther, boolean checkLibraries) throws ClassNotFoundException
    {
        // PaperProxy: BungeeCord has no Adventure, so plugins that need it ship their own copy,
        // in the jar or added at runtime (libby and similar add URLs to this loader). The proxy
        // has another Adventure version; the plugin's copy must win, otherwise its serializers
        // call methods the proxy's version does not have.
        if ( name.startsWith( "net.kyori." ) )
        {
            synchronized ( getClassLoadingLock( name ) )
            {
                Class<?> own = findLoadedClass( name );
                if ( own == null )
                {
                    try
                    {
                        own = findClass( name );
                    } catch ( ClassNotFoundException ex )
                    {
                        // not shipped by the plugin: use the proxy's
                    }
                }
                if ( own != null )
                {
                    if ( resolve )
                    {
                        resolveClass( own );
                    }
                    return own;
                }
            }
        }

        try
        {
            Class<?> result = super.loadClass( name, resolve );

            // SPIGOT-6749: Library classes will appear in the above, but we don't want to return them to other plugins
            if ( checkOther || result.getClassLoader() == this )
            {
                return result;
            }
        } catch ( ClassNotFoundException ex )
        {
        }

        if ( checkLibraries && libraryLoader != null )
        {
            try
            {
                return libraryLoader.loadClass( name );
            } catch ( ClassNotFoundException ex )
            {
            }
        }

        if ( checkOther )
        {
            for ( PluginClassloader loader : allLoaders )
            {
                if ( loader != this )
                {
                    try
                    {
                        return loader.loadClass0( name, resolve, false, proxy.getPluginManager().isTransitiveDepend( desc, loader.desc ) );
                    } catch ( ClassNotFoundException ex )
                    {
                    }
                }
            }
        }

        throw new ClassNotFoundException( name );
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException
    {
        String path = name.replace( '.', '/' ).concat( ".class" );
        JarEntry entry = jar.getJarEntry( path );

        if ( entry != null )
        {
            byte[] classBytes;

            try ( InputStream is = jar.getInputStream( entry ) )
            {
                classBytes = ByteStreams.toByteArray( is );
            } catch ( IOException ex )
            {
                throw new ClassNotFoundException( name, ex );
            }

            try
            {
                classBytes = remap( classBytes );
            } catch ( Exception ex )
            {
                Logger logger = ( plugin != null ) ? plugin.getLogger() : proxy.getLogger();
                logger.log( Level.SEVERE, "Error trying to remap class " + path, ex );
            }

            int dot = name.lastIndexOf( '.' );
            if ( dot != -1 )
            {
                String pkgName = name.substring( 0, dot );
                if ( getPackage( pkgName ) == null )
                {
                    try
                    {
                        if ( manifest != null )
                        {
                            definePackage( pkgName, manifest, url );
                        } else
                        {
                            definePackage( pkgName, null, null, null, null, null, null, null );
                        }
                    } catch ( IllegalArgumentException ex )
                    {
                        if ( getPackage( pkgName ) == null )
                        {
                            throw new IllegalStateException( "Cannot find package " + pkgName );
                        }
                    }
                }
            }

            CodeSigner[] signers = entry.getCodeSigners();
            CodeSource source = new CodeSource( url, signers );

            return defineClass( name, classBytes, 0, classBytes.length, source );
        }

        return super.findClass( name );
    }

    private static final Map<String, String> MAPPINGS = ImmutableMap.of(
            "net/md_5/bungee/protocol/ChatChain", "net/md_5/bungee/protocol/data/ChatChain",
            "net/md_5/bungee/protocol/Location", "net/md_5/bungee/protocol/data/Location",
            "net/md_5/bungee/protocol/NumberFormat", "net/md_5/bungee/protocol/data/NumberFormat",
            "net/md_5/bungee/protocol/PlayerPublicKey", "net/md_5/bungee/protocol/data/PlayerPublicKey",
            "net/md_5/bungee/protocol/Property", "net/md_5/bungee/protocol/data/Property",
            "net/md_5/bungee/protocol/SeenMessages", "net/md_5/bungee/protocol/data/SeenMessages",
            "net/md_5/bungee/protocol/Either", "net/md_5/bungee/protocol/util/Either",
            "net/md_5/bungee/protocol/TagUtil", "net/md_5/bungee/protocol/util/TagUtil"
    );

    private static byte[] remap(byte[] b)
    {
        ClassReader cr = new ClassReader( b );
        ClassWriter cw = new ClassWriter( cr, 0 );

        cr.accept( new ClassRemapper( cw, new SimpleRemapper( MAPPINGS ) ), 0 );

        return cw.toByteArray();
    }

    @Override
    public void close() throws IOException
    {
        try
        {
            super.close();
        } finally
        {
            jar.close();
        }
    }

    void init(Plugin plugin)
    {
        Preconditions.checkArgument( plugin != null, "plugin" );
        Preconditions.checkArgument( plugin.getClass().getClassLoader() == this, "Plugin has incorrect ClassLoader" );
        if ( this.plugin != null )
        {
            throw new IllegalArgumentException( "Plugin already initialized!" );
        }

        this.plugin = plugin;
        plugin.init( proxy, desc );
    }

    @Override
    public String toString()
    {
        return "PluginClassloader(desc=" + desc + ")";
    }
}
