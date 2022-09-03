// Npgsql.NpgsqlClosedState.cs
//
// Authors:
// 	Dave Joyner			<d4ljoyn@yahoo.com>
//	Daniel Nauck		<dna(at)mono-project.de>
//
//	Copyright (C) 2002 The Npgsql Development Team
//	npgsql-general@gborg.postgresql.org
//	http://gborg.postgresql.org/project/npgsql/projdisplay.php
//
// Permission to use, copy, modify, and distribute this software and its
// documentation for any purpose, without fee, and without a written
// agreement is hereby granted, provided that the above copyright notice
// and this paragraph and the following two paragraphs appear in all copies.
// 
// IN NO EVENT SHALL THE NPGSQL DEVELOPMENT TEAM BE LIABLE TO ANY PARTY
// FOR DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES,
// INCLUDING LOST PROFITS, ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS
// DOCUMENTATION, EVEN IF THE NPGSQL DEVELOPMENT TEAM HAS BEEN ADVISED OF
// THE POSSIBILITY OF SUCH DAMAGE.
// 
// THE NPGSQL DEVELOPMENT TEAM SPECIFICALLY DISCLAIMS ANY WARRANTIES,
// INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY
// AND FITNESS FOR A PARTICULAR PURPOSE. THE SOFTWARE PROVIDED HEREUNDER IS
// ON AN "AS IS" BASIS, AND THE NPGSQL DEVELOPMENT TEAM HAS NO OBLIGATIONS
// TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.

using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography.X509Certificates;
using System.Threading;
#if NETSTANDARD2_0
using System.Net.Security;
using System.Security.Authentication;
#else
using Mono.Security.Protocol.Tls;
using SecurityProtocolType = Mono.Security.Protocol.Tls.SecurityProtocolType;
#endif

namespace Revenj.DatabasePersistence.Postgres.Npgsql
{
	internal class NpgsqlNetworkStream : NetworkStream
	{
		NpgsqlConnector mContext = null;

		public NpgsqlNetworkStream(NpgsqlConnector context, Socket socket, Boolean owner)
			: base(socket, owner)
		{
			mContext = context;
		}

		protected override void Dispose(bool disposing)
		{
			if (!disposing)
			{
				mContext.Close();
				mContext = null;
			}
			base.Dispose(disposing);
		}
	}

	internal sealed class NpgsqlClosedState : NpgsqlState
	{
		private static readonly NpgsqlClosedState _instance = new NpgsqlClosedState();
		private static readonly String CLASSNAME = MethodBase.GetCurrentMethod().DeclaringType.Name;

		private NpgsqlClosedState() { }

		public static NpgsqlClosedState Instance
		{
			get
			{
				return _instance;
			}
		}

		/// <summary>
		/// Resolve a host name or IP address.
		/// This is needed because if you call Dns.Resolve() with an IP address, it will attempt
		/// to resolve it as a host name, when it should just convert it to an IP address.
		/// </summary>
		/// <param name="HostName"></param>
		private static IPAddress[] ResolveIPHost(String HostName)
		{
			return Dns.GetHostAddresses(HostName);
		}

#if NETSTANDARD2_0
		private static bool DefaultUserCertificateValidationCallback(object sender, X509Certificate certificate, X509Chain chain, SslPolicyErrors sslPolicyErrors)
			=> sslPolicyErrors == SslPolicyErrors.None;
#endif

		public override void Open(NpgsqlConnector context)
		{
			try
			{
				IPAddress[] ips = ResolveIPHost(context.Host);
				Socket socket = null;

				// try every ip address of the given hostname, use the first reachable one
				foreach (IPAddress ip in ips)
				{
					IPEndPoint ep = new IPEndPoint(ip, context.Port);
					socket = new Socket(ep.AddressFamily, SocketType.Stream, ProtocolType.Tcp);

					try
					{
						IAsyncResult result = socket.BeginConnect(ep, null, null);

						if (!result.AsyncWaitHandle.WaitOne(context.ConnectionTimeout * 1000, true))
						{
							socket.Close();
							throw new Exception("Connection establishment timeout. Increase Timeout value in ConnectionString.");
						}

						socket.EndConnect(result);

						// connect was successful, leave the loop
						break;
					}
					catch (Exception)
					{
						socket.Close();
					}
				}

				if (socket == null || !socket.Connected)
				{
					throw new Exception(string.Format("Failed to establish a connection to '{0}'.", context.Host));
				}

				Stream stream = new NpgsqlNetworkStream(context, socket, true);

				// If the PostgreSQL server has SSL connectors enabled Open SslClientStream if (response == 'S') {
				if (context.SSL || (context.SslMode == SslMode.Require) || (context.SslMode == SslMode.Prefer))
				{
					PGUtil.WriteInt32(stream, 8);
					PGUtil.WriteInt32(stream, 80877103);
					// Receive response

					Char response = (Char)stream.ReadByte();
					if (response == 'S')
					{
						//create empty collection
						X509CertificateCollection clientCertificates = new X509CertificateCollection();

						//trigger the callback to fetch some certificates
						context.DefaultProvideClientCertificatesCallback(clientCertificates);

#if NETSTANDARD2_0
						RemoteCertificateValidationCallback certificateValidationCallback;
						if (context.TrustServerCertificate)
							certificateValidationCallback = (sender, certificate, chain, errors) => true;
						else if (context.UserCertificateValidationCallback != null)
							certificateValidationCallback = context.UserCertificateValidationCallback;
						else
							certificateValidationCallback = DefaultUserCertificateValidationCallback;

						var sslStream = new SslStream(stream, false, certificateValidationCallback);
						sslStream.AuthenticateAsClient(context.Host, clientCertificates, 
							SslProtocols.Tls | SslProtocols.Tls11 | SslProtocols.Tls12, 
							context.CheckCertificateRevocation);
						stream = sslStream;
#else
						stream = new SslClientStream(
							stream,
							context.Host,
							true,
							SecurityProtocolType.Default,
							clientCertificates);

						((SslClientStream)stream).ClientCertSelectionDelegate =
							new CertificateSelectionCallback(context.DefaultCertificateSelectionCallback);
						((SslClientStream)stream).ServerCertValidationDelegate =
							new CertificateValidationCallback(context.DefaultCertificateValidationCallback);
						((SslClientStream)stream).PrivateKeyCertSelectionDelegate =
							new PrivateKeySelectionCallback(context.DefaultPrivateKeySelectionCallback);

#endif
					}
					else if (context.SslMode == SslMode.Require)
					{
						throw new InvalidOperationException("Ssl connection requested. No Ssl enabled connection from this host is configured.");
					}
				}

				context.Stream = new NpgsqlBufferedStream(stream);
				context.Socket = socket;

				ChangeState(context, NpgsqlConnectedState.Instance);
			}
			//FIXME: Exceptions that come from what we are handling should be wrapped - e.g. an error connecting to
			//the server should definitely be presented to the uesr as an NpgsqlError. Exceptions from userland should
			//be passed untouched - e.g. ThreadAbortException because the user started this in a thread they created and
			//then aborted should be passed through.
			//Are there any others that should be pass through? Alternatively, are there a finite number that should
			//be wrapped?
			catch (ThreadAbortException)
			{
				throw;
			}
			catch (Exception e)
			{
				throw new NpgsqlException(e.Message, e);
			}
		}

		public override void Close(NpgsqlConnector context)
		{
			//DO NOTHING.
		}
	}
}
