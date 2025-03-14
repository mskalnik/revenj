//    Copyright (C) 2002 The Npgsql Development Team
//    npgsql-general@gborg.postgresql.org
//    http://gborg.postgresql.org/project/npgsql/projdisplay.php
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
//
//    Connector.cs
// ------------------------------------------------------------------
//    Project
//        Npgsql
//    Status
//        0.00.0000 - 06/17/2002 - ulrich sprick - created
//                  - 06/??/2004 - Glen Parker<glenebob@nwlink.com> rewritten

using System;
using System.Collections.Generic;
using System.Data;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
#if NETSTANDARD2_0
using System.Net.Security;
#else
using Mono.Security.Protocol.Tls;
#endif
using Revenj.DatabasePersistence.Postgres.NpgsqlTypes;

namespace Revenj.DatabasePersistence.Postgres.Npgsql
{
	/// <summary>
	/// Represents the method that allows the application to provide a certificate collection to be used for SSL clien authentication
	/// </summary>
	/// <param name="certificates">A <see cref="System.Security.Cryptography.X509Certificates.X509CertificateCollection">X509CertificateCollection</see> to be filled with one or more client certificates.</param>
	public delegate void ProvideClientCertificatesCallback(X509CertificateCollection certificates);

	/// <summary>
	/// !!! Helper class, for compilation only.
	/// Connector implements the logic for the Connection Objects to
	/// access the physical connection to the database, and isolate
	/// the application developer from connection pooling internals.
	/// </summary>
	internal class NpgsqlConnector
	{
		// Immutable.
		private readonly NpgsqlConnectionStringBuilder settings;

		/// <summary>
		/// Occurs on NoticeResponses from the PostgreSQL backend.
		/// </summary>
		internal event NoticeEventHandler Notice;

		/// <summary>
		/// Occurs on NotificationResponses from the PostgreSQL backend.
		/// </summary>
		internal event NotificationEventHandler Notification;

		/// <summary>
		/// Called to provide client certificates for SSL handshake.
		/// </summary>
		internal event ProvideClientCertificatesCallback ProvideClientCertificatesCallback;

#if !NETSTANDARD2_0
		/// <summary>
		/// Mono.Security.Protocol.Tls.CertificateSelectionCallback delegate.
		/// </summary>
		internal event CertificateSelectionCallback CertificateSelectionCallback;

		/// <summary>
		/// Mono.Security.Protocol.Tls.CertificateValidationCallback delegate.
		/// </summary>
		internal event CertificateValidationCallback CertificateValidationCallback;

		/// <summary>
		/// Mono.Security.Protocol.Tls.PrivateKeySelectionCallback delegate.
		/// </summary>
		internal event PrivateKeySelectionCallback PrivateKeySelectionCallback;

#else
		/// <summary>
		/// Verifies the remote Secure Sockets Layer (SSL) certificate used for authentication.
		/// Ignored if <see cref="NpgsqlConnectionStringBuilder.TrustServerCertificate"/> is set.
		/// </summary>
		/// <remarks>
		/// See <see href="https://msdn.microsoft.com/en-us/library/system.net.security.remotecertificatevalidationcallback(v=vs.110).aspx"/>
		/// </remarks>
		public RemoteCertificateValidationCallback UserCertificateValidationCallback { get; set; }
#endif

		private ConnectionState _connection_state;

		private readonly HashSet<string> CachedPlans = new HashSet<string>();

		// The physical network connection to the backend.
		private NpgsqlBufferedStream _stream;

		private Socket _socket;

		// Mediator which will hold data generated from backend.
		private readonly NpgsqlMediator _mediator;

		private Version _serverVersion;

		// Values for possible CancelRequest messages.
		private NpgsqlBackEndKeyData _backend_keydata;

		private NpgsqlTransaction _transaction = null;

		private Boolean _supportsPrepare = false;

		private Boolean _supportsSavepoint = false;

		private NpgsqlBackendTypeMapping _oidToNameMapping = null;

		private Boolean _isInitialized;

		private readonly Boolean _pooled;
		private readonly Boolean _shared;

		private NpgsqlState _state;

		private Int32 _planIndex;
		private Int32 _portalIndex;

		private const String _planNamePrefix = "npgsqlplan";
		private const String _portalNamePrefix = "npgsqlportal";

		private CancellationTokenSource _notificationToken;

		// The AutoResetEvent to synchronize processing threads.
		internal AutoResetEvent _notificationAutoResetEvent;

		// Counter of notification thread start/stop requests in order to 
		internal Int16 _notificationThreadStopCount;

		private Exception _notificationException;

		internal ForwardsOnlyDataReader CurrentReader;

		// Some kinds of messages only get one response, and do not
		// expect a ready_for_query response.
		private bool _requireReadyForQuery = true;

		private bool? _useConformantStrings;

		private readonly Dictionary<string, NpgsqlParameterStatus> _serverParameters =
			new Dictionary<string, NpgsqlParameterStatus>(StringComparer.InvariantCultureIgnoreCase);

		public readonly byte[] TmpBuffer = new byte[4];
		public readonly ByteBuffer ArrayBuffer = new ByteBuffer();

#if WINDOWS && UNMANAGED

		private SSPIHandler _sspi;

		internal SSPIHandler SSPI
		{
			get { return _sspi; }
			set { _sspi = value; }
		}

#endif

		/// <summary>
		/// Constructor.
		/// </summary>
		/// <param name="Shared">Controls whether the connector can be shared.</param>
		public NpgsqlConnector(NpgsqlConnectionStringBuilder ConnectionString, bool Pooled, bool Shared)
		{
			this.settings = ConnectionString;
			State = ConnectionState.Closed;
			_pooled = Pooled;
			_shared = Shared;
			_isInitialized = false;
			_state = NpgsqlClosedState.Instance;
			_mediator = new NpgsqlMediator();
			_oidToNameMapping = new NpgsqlBackendTypeMapping();
			_planIndex = 0;
			_portalIndex = 0;
			_notificationThreadStopCount = 1;
			_notificationAutoResetEvent = new AutoResetEvent(true);
			rowDescription = new NpgsqlRowDescription(CompatVersion);
			rowReader = new StringRowReader(rowDescription, Stream, TmpBuffer, ArrayBuffer);
			forwardReader = new ForwardsOnlyRow(rowReader);
		}

		public NpgsqlConnector(NpgsqlConnection Connection)
			: this(Connection.ConnectionStringValues.Clone(), Connection.Pooling, false)
		{ }

		internal String Host
		{
			get { return settings.Host; }
		}

		internal Int32 Port
		{
			get { return settings.Port; }
		}

		internal String Database
		{
			get { return settings.ContainsKey(Keywords.Database) ? settings.Database : settings.UserName; }
		}

		internal String UserName
		{
			get { return settings.UserName; }
		}

		internal byte[] Password
		{
			get { return settings.PasswordAsByteArray; }
		}

		internal Boolean SSL
		{
			get { return settings.SSL; }
		}

		internal SslMode SslMode
		{
			get { return settings.SslMode; }
		}

		internal Int32 ConnectionTimeout
		{
			get { return settings.Timeout; }
		}

		internal Int32 CommandTimeout
		{
			get { return settings.CommandTimeout; }
		}

		internal Boolean Enlist
		{
			get { return settings.Enlist; }
		}

		public bool UseExtendedTypes
		{
			get { return settings.UseExtendedTypes; }
		}

		internal Boolean IntegratedSecurity
		{
			get { return settings.IntegratedSecurity; }
		}

#if NETSTANDARD2_0
		internal Boolean TrustServerCertificate
		{
			get { return settings.TrustServerCertificate; }
		}

		internal Boolean CheckCertificateRevocation
		{
			get { return settings.CheckCertificateRevocation; }
		}
#endif

		/// <summary>
		/// Gets the current state of the connection.
		/// </summary>
		internal ConnectionState State
		{
			get
			{
				if (_connection_state != ConnectionState.Closed && CurrentReader != null && !CurrentReader._cleanedUp)
				{
					return ConnectionState.Open | ConnectionState.Fetching;
				}
				return _connection_state;
			}
			private set
			{
				if (_connection_state != value)
				{
					var old = _connection_state;
					_connection_state = value;
					var sc = StateChanged;
					if (sc != null)
						sc(this, new StateChangeEventArgs(old, value));
				}
			}
		}

		internal event StateChangeEventHandler StateChanged;

		/// <summary>
		/// Return Connection String.
		/// </summary>
		internal string ConnectionString
		{
			get { return settings.ConnectionString; }
		}

		// State
		internal void Query(NpgsqlCommand queryCommand)
		{
			CurrentState.Query(this, queryCommand);
		}

		internal void PrepareOrAdd(string planName, string types, string query)
		{
			if (!CachedPlans.Contains(planName))
			{
				using (NpgsqlCommand cmd = new NpgsqlCommand("PREPARE \"" + planName + "\"(" + types + ") AS " + query, this))
					Query(cmd);
				CachedPlans.Add(planName);
			}
		}

		internal IEnumerable<IServerResponseObject> QueryEnum(NpgsqlCommand queryCommand)
		{
			if (CurrentReader != null)
			{
				if (!CurrentReader._cleanedUp)
				{
					throw new InvalidOperationException(
						"There is already an open DataReader associated with this Command which must be closed first.");
				}
				CurrentReader.Close();
			}
			return CurrentState.QueryEnum(this, queryCommand);
		}

		internal void Authenticate(byte[] password)
		{
			CurrentState.Authenticate(this, password);
		}

		internal void Parse(NpgsqlParse parse)
		{
			CurrentState.Parse(this, parse);
		}

		internal void Flush()
		{
			CurrentState.Flush(this);
		}

		internal void TestConnector()
		{
			CurrentState.TestConnector(this);
		}

		internal NpgsqlRowDescription Sync()
		{
			return CurrentState.Sync(this);
		}

		internal void Bind(NpgsqlBind bind)
		{
			CurrentState.Bind(this, bind);
		}

		internal void Describe(NpgsqlDescribe describe)
		{
			CurrentState.Describe(this, describe);
		}

		internal void Execute(NpgsqlExecute execute)
		{
			CurrentState.Execute(this, execute);
		}

		internal IEnumerable<IServerResponseObject> ExecuteEnum(NpgsqlExecute execute)
		{
			return CurrentState.ExecuteEnum(this, execute);
		}

		private static int ValidCounter;

		/// <summary>
		/// This method checks if the connector is still ok.
		/// We try to send a simple query text, select 1 as ConnectionTest;
		/// </summary>
		internal Boolean IsValid()//TODO: WTF!?
		{
			try
			{
				// Here we use a fake NpgsqlCommand, just to send the test query string.

				var testValue = (ValidCounter++).ToString();

				string compareValue = string.Empty;
				using (NpgsqlCommand cmd = new NpgsqlCommand("select '" + testValue + "'", this))
				{
					compareValue = (string)cmd.ExecuteScalar();
				}

				if (compareValue != testValue)
					return false;

				// Clear mediator.
				Mediator.ResetResponses();
				this.RequireReadyForQuery = true;

			}
			catch
			{
				return false;
			}

			return true;
		}
		/// <summary>
		/// This method is responsible for releasing all resources associated with this Connector.
		/// </summary>
		internal void ReleaseResources()
		{
			if (_connection_state != ConnectionState.Closed)
			{
				ReleasePlansPortals();
				ReleaseRegisteredListen();
			}
		}

		internal void ReleaseRegisteredListen()
		{
			//Query(new NpgsqlCommand("unlisten *", this));
			using (NpgsqlCommand cmd = new NpgsqlCommand("unlisten *", this))
			{
				Query(cmd);
			}
		}

		/// <summary>
		/// This method is responsible to release all portals used by this Connector.
		/// </summary>
		internal void ReleasePlansPortals()
		{
			Int32 i = 0;

			if (_planIndex > 0)
			{
				for (i = 1; i <= _planIndex; i++)
				{
					try
					{
						//Query(new NpgsqlCommand(String.Format("deallocate \"{0}\";", _planNamePrefix + i), this));
						using (NpgsqlCommand cmd = new NpgsqlCommand(String.Format("deallocate \"{0}\";", _planNamePrefix + i.ToString()), this))
						{
							Query(cmd);
						}
					}

					// Ignore any error which may occur when releasing portals as this portal name may not be valid anymore. i.e.: the portal name was used on a prepared query which had errors.
					catch { }
				}
			}
			foreach (var cp in CachedPlans)
			{
				try
				{
					using (NpgsqlCommand cmd = new NpgsqlCommand("deallocate \"" + cp + "\";", this))
						Query(cmd);
				}

				// Ignore any error which may occur when releasing portals as this portal name may not be valid anymore. i.e.: the portal name was used on a prepared query which had errors.
				catch { }
			}

			CachedPlans.Clear();
			_portalIndex = 0;
			_planIndex = 0;
		}

		internal void FireNotice(NpgsqlError e)
		{
			if (Notice != null)
			{
				try
				{
					Notice(this, new NpgsqlNoticeEventArgs(e));
				}
				catch
				{
				} //Eat exceptions from user code.
			}
		}

		internal void FireNotification(NpgsqlNotificationEventArgs e)
		{
			if (Notification != null)
			{
				try
				{
					Notification(this, e);
				}
				catch
				{
				} //Eat exceptions from user code.
			}
		}

#if !NETSTANDARD2_0
		/// <summary>
		/// Default SSL CertificateSelectionCallback implementation.
		/// </summary>
		internal X509Certificate DefaultCertificateSelectionCallback(X509CertificateCollection clientCertificates,
																	 X509Certificate serverCertificate, string targetHost,
																	 X509CertificateCollection serverRequestedCertificates)
		{
			if (CertificateSelectionCallback != null)
			{
				return CertificateSelectionCallback(clientCertificates, serverCertificate, targetHost, serverRequestedCertificates);
			}
			else
			{
				return null;
			}
		}

		/// <summary>
		/// Default SSL CertificateValidationCallback implementation.
		/// </summary>
		internal bool DefaultCertificateValidationCallback(X509Certificate certificate, int[] certificateErrors)
		{
			if (CertificateValidationCallback != null)
			{
				return CertificateValidationCallback(certificate, certificateErrors);
			}
			else
			{
				return true;
			}
		}

		/// <summary>
		/// Default SSL PrivateKeySelectionCallback implementation.
		/// </summary>
		internal AsymmetricAlgorithm DefaultPrivateKeySelectionCallback(X509Certificate certificate, string targetHost)
		{
			if (PrivateKeySelectionCallback != null)
			{
				return PrivateKeySelectionCallback(certificate, targetHost);
			}
			else
			{
				return null;
			}
		}
#endif

		/// <summary>
		/// Default SSL ProvideClientCertificatesCallback implementation.
		/// </summary>
		internal void DefaultProvideClientCertificatesCallback(X509CertificateCollection certificates)
		{
			if (ProvideClientCertificatesCallback != null)
			{
				ProvideClientCertificatesCallback(certificates);
			}
		}

		/// <summary>
		/// Version of backend server this connector is connected to.
		/// </summary>
		internal Version ServerVersion
		{
			get { return _serverVersion; }
			set { _serverVersion = value; }
		}

		/// <summary>
		/// The physical connection stream to the backend.
		/// </summary>
		internal NpgsqlBufferedStream Stream
		{
			get { return _stream; }
			set { _stream = value; }
		}

		private bool newDescription;

		private readonly NpgsqlRowDescription rowDescription;
		internal NpgsqlRowDescription RowDescription()
		{
			newDescription = true;
			return rowDescription.Process(_stream, OidToNameMapping, TmpBuffer, ArrayBuffer);
		}

		private readonly StringRowReader rowReader;
		private readonly ForwardsOnlyRow forwardReader;
		internal ForwardsOnlyRow NextRow()
		{
			newDescription = false;
			forwardReader.Reset();
			rowReader.NextMessage(_stream);
			return forwardReader;
		}

		/// <summary>
		/// The physical connection socket to the backend.
		/// </summary>
		internal Socket Socket
		{
			get { return _socket; }
			set { _socket = value; }
		}

		/// <summary>
		/// Reports if this connector is fully connected.
		/// </summary>
		internal Boolean IsInitialized
		{
			get { return _isInitialized; }
			set { _isInitialized = value; }
		}

		internal NpgsqlState CurrentState
		{
			get { return _state; }
			set { _state = value; }
		}


		internal bool Pooled
		{
			get { return _pooled; }
		}

		internal bool Shared
		{
			get { return _shared; }
		}

		internal NpgsqlBackEndKeyData BackEndKeyData
		{
			get { return _backend_keydata; }
			set { _backend_keydata = value; }
		}

		internal NpgsqlBackendTypeMapping OidToNameMapping
		{
			get { return _oidToNameMapping; }
		}

		internal Version CompatVersion
		{
			get
			{
				return settings.Compatible;
			}
		}

		/// <summary>
		/// The connection mediator.
		/// </summary>
		internal NpgsqlMediator Mediator
		{
			get { return _mediator; }
		}

		/// <summary>
		/// Report if the connection is in a transaction.
		/// </summary>
		internal NpgsqlTransaction Transaction
		{
			get { return _transaction; }
			set { _transaction = value; }
		}

		internal Boolean SupportsApplicationName
		{
			get { return ServerVersion >= new Version(9, 0, 0); }
		}

		/// <summary>
		/// Report whether the current connection can support prepare functionality.
		/// </summary>
		internal Boolean SupportsPrepare
		{
			get { return _supportsPrepare; }
			set { _supportsPrepare = value; }
		}

		internal Boolean SupportsSavepoint
		{
			get { return _supportsSavepoint; }
			set { _supportsSavepoint = value; }
		}

		/// <summary>
		/// This method is required to set all the version dependent features flags.
		/// SupportsPrepare means the server can use prepared query plans (7.3+)
		/// </summary>
		// FIXME - should be private
		internal void ProcessServerVersion()
		{
			this._supportsPrepare = (ServerVersion >= new Version(7, 3, 0));
			this._supportsSavepoint = (ServerVersion >= new Version(8, 0, 0));
		}

		/// <summary>
		/// Opens the physical connection to the server.
		/// </summary>
		/// <remarks>Usually called by the RequestConnector
		/// Method of the connection pool manager.</remarks>
		internal void Open()
		{
			ServerVersion = null;
			// If Connection.ConnectionString specifies a protocol version, we will
			// not try to fall back to version 2 on failure.

			// Reset state to initialize new connector in pool.
			CurrentState = NpgsqlClosedState.Instance;

			// Get a raw connection, possibly SSL...
			CurrentState.Open(this);
			try
			{
				// Establish protocol communication and handle authentication...
				CurrentState.Startup(this);
			}
			catch (NpgsqlException)
			{
				throw;
			}

			// Change the state of connection to open and ready.
			State = ConnectionState.Open;
			CurrentState = NpgsqlReadyState.Instance;

			// Fall back to the old way, SELECT VERSION().
			// This should not happen for protocol version 3+.
			if (ServerVersion == null)
			{
				using (NpgsqlCommand command = new NpgsqlCommand("set DATESTYLE TO ISO;select version();", this))
				{
					ServerVersion = new Version(PGUtil.ExtractServerVersion((string)command.ExecuteScalar()));
				}
			}

			StringBuilder sbInit = new StringBuilder();

			// Adjust client encoding.
			NpgsqlParameterStatus clientEncodingParam = null;
			if (!ServerParameters.TryGetValue("client_encoding", out clientEncodingParam)
				|| !string.Equals(clientEncodingParam.ParameterValue, "UTF8", StringComparison.OrdinalIgnoreCase) && !string.Equals(clientEncodingParam.ParameterValue, "UNICODE", StringComparison.OrdinalIgnoreCase))
				sbInit.AppendLine("SET CLIENT_ENCODING TO UTF8;");

			if (!string.IsNullOrEmpty(settings.SearchPath))
			{
				// TODO: Add proper message when finding a semicolon in search_path.
				// This semicolon could lead to a sql injection security hole as someone could write in connection string:
				// searchpath=public;delete from table; and it would be executed.

				if (settings.SearchPath.Contains(";"))
				{
					throw new InvalidOperationException();
				}

				sbInit.AppendLine("SET SEARCH_PATH=" + settings.SearchPath + ";");
			}

			if (!string.IsNullOrEmpty(settings.ApplicationName))
			{
				if (!SupportsApplicationName)
				{
					throw new InvalidOperationException("ApplicationName not supported.");
				}

				if (settings.ApplicationName.Contains(";"))
				{
					throw new InvalidOperationException();
				}

				sbInit.AppendLine("SET APPLICATION_NAME='" + settings.ApplicationName.Replace('\'', '-') + "';");
			}

			/*
			 * Try to set SSL negotiation to 0. As of 2010-03-29, recent problems in SSL library implementations made
			 * postgresql to add a parameter to set a value when to do this renegotiation or 0 to disable it.
			 * Currently, Npgsql has a problem with renegotiation so, we are trying to disable it here.
			 * This only works on postgresql servers where the ssl renegotiation settings is supported of course.
			 * See http://lists.pgfoundry.org/pipermail/npgsql-devel/2010-February/001065.html for more information.
			 */
			sbInit.AppendLine("SET ssl_renegotiation_limit=0;");

			/*
			 * Set precision digits to maximum value possible. For postgresql before 9 it was 2, after that, it is 3.
			 * This way, we set first to 2 and then to 3. If there is an error because of 3, it will have been set to 2 at least.
			 * Check bug report #1010992 for more information.
			 */
			sbInit.AppendLine("SET extra_float_digits=3;");
			try
			{
				new NpgsqlCommand(sbInit.ToString(), this).ExecuteBlind();
			}
			catch
			{
				foreach (var line in sbInit.ToString().Split(Environment.NewLine.ToCharArray()))
				{
					try
					{
						if (line.Length > 0)
						{
							new NpgsqlCommand(line, this).ExecuteBlind();
						}
					}
					catch { }
				}
			}

			// Make a shallow copy of the type mapping that the connector will own.
			// It is possible that the connector may add types to its private
			// mapping that will not be valid to another connector, even
			// if connected to the same backend version.
			_oidToNameMapping = NpgsqlTypesHelper.CreateAndLoadInitialTypesMapping(this).Clone();

			ProcessServerVersion();

			// The connector is now fully initialized. Beyond this point, it is
			// safe to release it back to the pool rather than closing it.
			IsInitialized = true;
		}

		/// <summary>
		/// Closes the physical connection to the server.
		/// </summary>
		internal void Close()
		{
			try
			{
				if (_connection_state != ConnectionState.Closed)
				{
					State = ConnectionState.Closed;
					this.CurrentState.Close(this);
					_serverParameters.Clear();
					ServerVersion = null;
				}
			}
			catch
			{
			}
		}

		internal void CancelRequest()
		{
			NpgsqlConnector cancelConnector = new NpgsqlConnector(settings, false, false);

			cancelConnector._backend_keydata = BackEndKeyData;

			try
			{
				// Get a raw connection, possibly SSL...
				cancelConnector.CurrentState.Open(cancelConnector);

				// Cancel current request.
				cancelConnector.CurrentState.CancelRequest(cancelConnector);
			}
			finally
			{
				cancelConnector.CurrentState.Close(cancelConnector);
			}
		}


		///<summary>
		/// Returns next portal index.
		///</summary>
		internal String NextPortalName()
		{
			return _portalNamePrefix + Interlocked.Increment(ref _portalIndex).ToString();
		}


		///<summary>
		/// Returns next plan index.
		///</summary>
		internal String NextPlanName()
		{
			return _planNamePrefix + Interlocked.Increment(ref _planIndex).ToString();
		}


		internal void RemoveNotificationThread()
		{
			// Kill notification thread.
			_notificationToken.Cancel();
			_notificationToken = null;

			// Special case in order to not get problems with thread synchronization.
			// It will be turned to 0 when synch thread is created.
			_notificationThreadStopCount = 1;
		}

		internal void AddNotificationThread()
		{
			_notificationThreadStopCount = 0;
			_notificationAutoResetEvent.Set();
			_notificationToken = new CancellationTokenSource();

			NpgsqlContextHolder contextHolder = new NpgsqlContextHolder(this, CurrentState, _notificationToken.Token);

			var _notificationThread = new Thread(new ThreadStart(contextHolder.ProcessServerMessages));

			_notificationThread.Start();
		}

		//Use with using(){} to perform the sentry pattern
		//on stopping and starting notification thread
		//(The sentry pattern is a generalisation of RAII where we
		//have a pair of actions - one "undoing" the previous
		//and we want to execute the first and second around other code,
		//then we treat it much like resource mangement in RAII.
		//try{}finally{} also does execute-around, but sentry classes
		//have some extra flexibility (e.g. they can be "owned" by
		//another object and then cleaned up when that object is
		//cleaned up), and can act as the sole gate-way
		//to the code in question, guaranteeing that using code can't be written
		//so that the "undoing" is forgotten.
		internal class NotificationThreadBlock : IDisposable
		{
			private NpgsqlConnector _connector;

			public NotificationThreadBlock(NpgsqlConnector connector)
			{
				(_connector = connector).StopNotificationThread();
			}

			public void Dispose()
			{
				if (_connector != null)
				{
					_connector.ResumeNotificationThread();
				}
				_connector = null;
			}
		}

		internal NotificationThreadBlock BlockNotificationThread()
		{
			return new NotificationThreadBlock(this);
		}

		private void StopNotificationThread()
		{
			// first check to see if an exception has
			// been thrown by the notification thread.
			if (_notificationException != null)
				throw _notificationException;

			_notificationThreadStopCount++;

			if (_notificationThreadStopCount == 1) // If this call was the first to increment.
			{
				_notificationAutoResetEvent.WaitOne();
			}
		}

		private void ResumeNotificationThread()
		{
			_notificationThreadStopCount--;
			if (_notificationThreadStopCount == 0)
			{
				// Release the synchronization handle.

				_notificationAutoResetEvent.Set();
			}
		}

		internal Boolean IsNotificationThreadRunning
		{
			get { return _notificationThreadStopCount <= 0; }
		}


		internal class NpgsqlContextHolder
		{
			private readonly NpgsqlConnector connector;
			private readonly NpgsqlState state;
			private readonly CancellationToken token;

			internal NpgsqlContextHolder(NpgsqlConnector connector, NpgsqlState state, CancellationToken token)
			{
				this.connector = connector;
				this.state = state;
				this.token = token;
			}

			internal void ProcessServerMessages()
			{
				try
				{
					while (!token.IsCancellationRequested)
					{
						Thread.Sleep(1);
						//To give runtime chance to release correctly the lock. See http://pgfoundry.org/forum/message.php?msg_id=1002650 for more information.
						this.connector._notificationAutoResetEvent.WaitOne();

						if (this.connector.Socket.Poll(100, SelectMode.SelectRead))
						{
							// reset any responses just before getting new ones
							this.connector.Mediator.ResetResponses();
							this.state.ProcessBackendResponsesWhileExists(this.connector);
						}

						this.connector._notificationAutoResetEvent.Set();
					}
				}
				catch (Exception ex)
				{
					this.connector._notificationException = ex;
				}
				finally
				{
					this.connector._notificationAutoResetEvent.Set();
					this.connector.Close();
				}
			}
		}

		public bool RequireReadyForQuery
		{
			get { return _requireReadyForQuery; }
			set { _requireReadyForQuery = value; }
		}

		public void AddParameterStatus(NpgsqlParameterStatus ps)
		{
			if (_serverParameters.ContainsKey(ps.Parameter))
			{
				_serverParameters[ps.Parameter] = ps;
			}
			else
			{
				_serverParameters.Add(ps.Parameter, ps);
			}
			_useConformantStrings = null;
		}

		public IDictionary<string, NpgsqlParameterStatus> ServerParameters
		{
			get { return new ReadOnlyDictionary<string, NpgsqlParameterStatus>(_serverParameters); }
		}
		public string CheckParameter(string paramName)
		{
			NpgsqlParameterStatus ps = null;
			if (_serverParameters.TryGetValue(paramName, out ps))
				return ps.ParameterValue;
			try
			{
				using (NpgsqlCommand cmd = new NpgsqlCommand("show " + paramName, this))
				{
					string paramValue = (string)cmd.ExecuteScalar();
					AddParameterStatus(new NpgsqlParameterStatus(paramName, paramValue));
					return paramValue;
				}
			}

			/*
			 * In case of problems with the command above, we simply return null in order to 
			 * say we don't support it.
			 */

			catch (NpgsqlException)
			{
				return null;
			}
			/*
			 * Original catch handler by Jon Hanna.
			 * 7.3 version doesn't support error code. Only 7.4+
			 
			 * catch(NpgsqlException ne)
			{
				if(ne.Code == "42704")//unrecognized configuration parameter
					return null;
				else
					throw;
			}*/
		}
		private bool CheckStringConformanceRequirements()
		{
			//If standards_conforming_strings is "on" then postgres will handle \ in strings differently to how we expect, unless
			//an escaped-string literal is use (E'example\n' rather than 'example\n'. At time of writing this defaults
			//to "off", but documentation says this will change to default "on" in the future, and it can still be "on" now.
			//escape_string_warning being "on" (current default) will result in a warning, but not an error, on every such
			//string, which is not ideal.
			//On the other hand, earlier versions of postgres do not have the escaped-literal syntax and will error if it
			//is used. Version numbers could be checked, but here the approach taken is to check on what the server is
			//explicitly requesting.

			NpgsqlParameterStatus warning = null;
			if (_serverParameters.TryGetValue("escape_string_warning", out warning) && warning.ParameterValue == "on")//try the most commonly set at time of coding first
				return true;
			NpgsqlParameterStatus insist = null;
			if (_serverParameters.TryGetValue("standard_conforming_strings", out insist) && insist.ParameterValue == "on")
				return true;
			if (warning != null && insist != null)//both where present and "off".
				return false;
			//We need to check at least one of these on the server.
			return CheckParameter("standard_conforming_strings") == "on" || CheckParameter("escape_string_warning") == "on";
		}
		public bool UseConformantStrings
		{
			get
			{
				return _useConformantStrings ?? (_useConformantStrings = CheckStringConformanceRequirements()).Value;
			}
		}
	}
}
